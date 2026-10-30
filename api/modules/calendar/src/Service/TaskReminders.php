<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Message\CheckRoutineCommand;
use Maggie\Calendar\Message\SetDayPlanCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\MessageHandler\AnswerTaskReminderHandler;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Calendar\Repository\TaskReminderRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\UseCase\DeleteNotification;
use Maggie\Notification\UseCase\UpdateNotification;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The task reminders (MAG-510): planned at moments of the day, sent under
 * three rules, answered in one gesture. Called every minute by
 * `maggie:notification:check-reminders`.
 *
 * - `due` (MAG-718): a task at a precise time, at each of its reminders —
 *   minutes before the time, as an event. The morning alerts of
 *   {@see TaskAlertSchedule} send nothing anymore: the morning digest reads them;
 * - `nudge`: at `afterBreakfast` and `afterLunch`, the task « now » of a
 *   confirmed day, once per moment and per day;
 * - `routine` (MAG-512): at each moment and each hour of the routines that ask
 *   to be reminded, one reminder for those due and not ticked
 *   ({@see RoutineReminders}), once per moment and per day.
 *
 * The rules hold for every kind: nothing in the quiet hours — a reminder that
 * falls in them is dropped, never piled up for the morning —, at most
 * `reminderDailyCap` a local day (those the user put off or set aside), and never the
 * message of the previous reminder of the same kind. A new kind plugs in at
 * {@see self::compose()}.
 *
 * @phpstan-type Composed array{type: NotificationType, key: string, title: string, body: string|null, actions: list<string>, iri: string}
 */
final class TaskReminders
{
    /** How long after its time a reminder still goes out: a minute lost to a restart must not cost it. */
    private const GRACE = '+10 minutes';

    /** « Dans 10 min ». */
    private const SNOOZE = '+10 minutes';

    private const NUDGE_MOMENTS = [DayMoment::AfterBreakfast, DayMoment::AfterLunch];

    public function __construct(
        private readonly TaskReminderRepository $reminders,
        private readonly TaskSettingsRepository $settings,
        private readonly TaskOverdue $overdue,
        private readonly DayPlanRepository $dayPlanRepository,
        private readonly DayPlans $dayPlans,
        private readonly TaskRepository $tasks,
        private readonly RoutineReminders $routineReminders,
        private readonly RewardRepository $rewards,
        private readonly ReminderMessages $messages,
        private readonly UpdateNotification $updateNotification,
        private readonly DeleteNotification $deleteNotification,
        private readonly MessageBusInterface $bus,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    /** Plans what falls now for the user, then sends what is due. Returns the notifications sent. */
    public function run(User $user): int
    {
        $settings = $this->settingsOf($user);
        $local = $this->local($user);

        $this->planTimed($user, $local);
        $this->planNudges($user, $settings, $local);
        $this->planRoutines($user, $local);
        $this->em->flush();

        return $this->sendPending($user, $settings, $local);
    }

    /**
     * Carries out the button pressed on a sent reminder.
     *
     * @return array{reminder: array<string, mixed>, next: array<string, mixed>|null, reward: array<string, mixed>|null, notification: array{id: string, title: string, body: string|null, actions: list<string>}|null}
     *
     * @throws \InvalidArgumentException on an unknown action
     * @throws \DomainException          when the reminder cannot take it now: not sent, answered already, its task gone, tonight passed, tomorrow full
     */
    public function act(TaskReminder $reminder, string $action): array
    {
        if (!\in_array($action, TaskReminder::ACTIONS, true)) {
            throw new \InvalidArgumentException(\sprintf('action must be one of %s, not "%s".', implode(', ', TaskReminder::ACTIONS), $action));
        }
        if (null === $reminder->getSentAt()) {
            throw new \DomainException('This reminder was not sent: there is nothing to answer.');
        }
        if (null !== $reminder->getAction()) {
            throw new \DomainException(\sprintf('This reminder was already answered: "%s".', $reminder->getAction()));
        }
        if (!\in_array($action, TaskReminder::ACTIONS_BY_KIND[$reminder->getKind()] ?? [], true)) {
            throw new \DomainException(\sprintf('A "%s" reminder is answered with %s, not "%s".', $reminder->getKind(), implode(', ', TaskReminder::ACTIONS_BY_KIND[$reminder->getKind()] ?? []), $action));
        }
        // Claimed first: a second press, on the phone and in the admin at once, finds it answered.
        if (!$this->reminders->claim($reminder, $action)) {
            throw new \DomainException('This reminder was already answered.');
        }

        $user = $reminder->getUser();
        $isRoutine = TaskReminder::KIND_ROUTINE === $reminder->getKind();
        $task = $isRoutine ? null : ($this->task($user, $reminder->getSubjectId())
            ?? throw new \DomainException('The task of this reminder no longer exists.'));
        $settings = $this->settingsOf($user);
        $local = $this->local($user);
        $notification = $reminder->getNotification();
        $next = null;
        $reward = null;

        switch ($action) {
            case TaskReminder::ACTION_IN10:
                $next = $this->plan($user, $reminder->getKind(), $reminder->getSubjectId(), $reminder->getMoment(), $local->modify(self::SNOOZE), true);
                break;
            case TaskReminder::ACTION_TONIGHT:
                \assert(null !== $task);
                if (!$this->tonightAhead($settings, $local)) {
                    throw new \DomainException('Tonight has passed: put it in tomorrow instead.');
                }
                $next = $this->plan($user, $reminder->getKind(), $reminder->getSubjectId(), DayMoment::AfterDinner, self::at($local, $settings->getDayMoment(DayMoment::AfterDinner)), true);
                break;
            case TaskReminder::ACTION_TOMORROW:
                \assert(null !== $task);
                $this->putInTomorrow($user, $task);
                break;
            case TaskReminder::ACTION_DONE:
                if ($isRoutine) {
                    // A tick, nothing more: no star, no bravo for a routine.
                    $this->tickRoutine($reminder);
                    break;
                }
                \assert(null !== $task);
                if (!$task->isDone()) {
                    $this->bus->dispatch(new UpdateTaskCommand(taskId: (string) $task->getId(), completedAt: $this->clock->now()));
                }
                $reward = $this->rewards->findOneBySubject($user, Reward::SUBJECT_TASK, (string) $task->getId());
                break;
        }

        $this->em->flush();

        if (null !== $notification) {
            if (TaskReminder::ACTION_IN10 === $action || TaskReminder::ACTION_TONIGHT === $action) {
                // Put off, it goes away and comes back as a new one.
                $reminder->setNotification(null);
                $this->em->flush();
                $this->deleteNotification->execute($notification);
                $notification = null;
            } else {
                // Answered, it stays as read, with nothing left to press; « Fait » says the bravo instead.
                if (null !== $reward) {
                    $notification->setTitle(mb_strimwidth($reward->getMessage(), 0, ReminderMessages::TITLE_MAX, '…'))->setBody(null);
                }
                $notification->setActions(null);
                $notification->setReadAt($notification->getReadAt() ?? $this->clock->now());
                $this->updateNotification->execute($notification);
            }
        }

        return [
            'reminder' => $reminder->describe(),
            'next' => $next?->describe(),
            'reward' => $reward?->describe(),
            'notification' => null === $notification ? null : [
                'id' => (string) $notification->getId(),
                'title' => $notification->getTitle(),
                'body' => $notification->getBody(),
                'actions' => $notification->getActions(),
            ],
        ];
    }

    /**
     * Each reminder of the tasks at a precise time, at its minute: once per
     * task and per minute, so a task moved to another time is reminded again.
     */
    private function planTimed(User $user, \DateTimeImmutable $local): void
    {
        $zone = $local->getTimezone();
        $today = $this->overdue->today($user);
        // The furthest reminder is a week before: a task up to a week ahead may fall now.
        $until = $today->modify(\sprintf('+%d days', intdiv(TaskSchedule::MAX_REMINDER_MINUTES, 1440)));

        foreach ($this->tasks->findOpenTimedBetween($user, $today, $until) as $task) {
            $taskId = (string) $task->getId();
            foreach (self::timedAt($task, $zone) as $at) {
                if (self::within($local, $at) && !$this->reminders->isPlanned($user, TaskReminder::KIND_DUE, $at, $at->modify('+1 second'), subjectId: $taskId)) {
                    $this->plan($user, TaskReminder::KIND_DUE, $taskId, null, $at);
                }
            }
        }
    }

    /**
     * The minutes the reminders of a task at a precise time fall at, in $zone.
     *
     * @return array<int, \DateTimeImmutable> by delay, minutes before the time
     */
    private static function timedAt(Task $task, \DateTimeZone $zone): array
    {
        $start = TaskSchedule::startsAt($task, $zone);
        if (null === $start) {
            return [];
        }

        $at = [];
        foreach ($task->getReminders() as $delay) {
            $at[$delay] = $start->modify(\sprintf('-%d minutes', $delay));
        }

        return $at;
    }

    private function planNudges(User $user, TaskSettings $settings, \DateTimeImmutable $local): void
    {
        [$from, $to] = self::dayOf($local);
        $nowTaskId = null;

        foreach (self::NUDGE_MOMENTS as $moment) {
            $at = self::at($local, $settings->getDayMoment($moment));
            if (!self::within($local, $at) || $this->reminders->isPlanned($user, TaskReminder::KIND_NUDGE, $from, $to, moment: $moment)) {
                continue;
            }

            $nowTaskId ??= $this->nowTaskId($user);
            if (null !== $nowTaskId) {
                $this->plan($user, TaskReminder::KIND_NUDGE, $nowTaskId, $moment, $at);
            }
        }
    }

    /** One reminder per moment (and per hour) with routines to tick, once a day. */
    private function planRoutines(User $user, \DateTimeImmutable $local): void
    {
        $today = $this->overdue->today($user);
        [$from, $to] = self::dayOf($local);

        foreach ($this->routineReminders->groups($user, $today) as $group) {
            $at = self::at($local, $group['time']);
            $subject = RoutineReminders::subject($today, $group['key']);
            if (self::within($local, $at) && !$this->reminders->isPlanned($user, TaskReminder::KIND_ROUTINE, $from, $to, subjectId: $subject)) {
                $this->plan($user, TaskReminder::KIND_ROUTINE, $subject, $group['moment'], $at);
            }
        }
    }

    /**
     * « Fait » of a routine reminder, offered when one routine is left: it
     * ticks that one, on the day of the reminder. Several now (one was added
     * since) is not for one gesture.
     */
    private function tickRoutine(TaskReminder $reminder): void
    {
        $user = $reminder->getUser();
        [$day, $key] = RoutineReminders::parse($reminder->getSubjectId());
        $routines = $this->routineReminders->group($user, $day, $key)['routines'] ?? [];

        if (\count($routines) > 1) {
            throw new \DomainException('Several routines wait for this moment: open them to tick each one.');
        }
        foreach ($routines as $routine) {
            $this->bus->dispatch(new CheckRoutineCommand((string) $user->getId(), (string) $routine->getId(), $day));
        }
    }

    /** The task « now » of today, when the day is confirmed. */
    private function nowTaskId(User $user): ?string
    {
        $plan = $this->dayPlanRepository->findOneByUserAndDate($user, $this->overdue->today($user));
        if (!$plan instanceof DayPlan || null === $plan->getConfirmedAt()) {
            return null;
        }

        $now = $this->dayPlans->describe($plan)['now'];

        return null === $now ? null : $now['task']['id'];
    }

    private function sendPending(User $user, TaskSettings $settings, \DateTimeImmutable $local): int
    {
        $now = $this->clock->now();
        [$from, $to] = self::dayOf($local);
        $sent = 0;

        foreach ($this->reminders->findPending($user, $now) as $reminder) {
            $composed = $this->mayGo($reminder, $settings, $local, $from, $to) ? $this->compose($reminder, $settings, $local) : null;
            $notification = null === $composed ? null : $this->notify($user, $reminder, $composed);

            if (null === $composed || null === $notification) {
                $reminder->setDroppedAt($now);
            } else {
                $reminder->setSentAt($now)->setMessageKey($composed['key'])->setNotification($notification);
                ++$sent;
            }
            $this->em->flush();
        }

        return $sent;
    }

    /** The rules every kind keeps: on time, outside the quiet hours, under the daily cap unless put off or asked for by the user. */
    private function mayGo(TaskReminder $reminder, TaskSettings $settings, \DateTimeImmutable $local, \DateTimeImmutable $from, \DateTimeImmutable $to): bool
    {
        // Missed, it is not sent late: never piled up after the quiet hours or a restart.
        if ($local >= $reminder->getScheduledFor()->modify(self::GRACE) || $settings->isQuiet($local->format('H:i'))) {
            return false;
        }

        // A reminder at a precise time is one the user set, as on an event: the cap is for Maggie's own.
        return TaskReminder::KIND_DUE === $reminder->getKind() || $reminder->isSnoozed() || $this->reminders->countCappedSentBetween($reminder->getUser(), $from, $to) < $settings->getReminderDailyCap();
    }

    /**
     * The notification a reminder sends, or null when it has nothing left to
     * say (its task done or gone, no longer alerted).
     *
     * @return Composed|null
     */
    private function compose(TaskReminder $reminder, TaskSettings $settings, \DateTimeImmutable $local): ?array
    {
        $user = $reminder->getUser();
        $previous = $this->reminders->findLastMessageKey($user, $reminder->getKind());
        if (TaskReminder::KIND_ROUTINE === $reminder->getKind()) {
            return $this->composeRoutine($reminder, $settings, $previous);
        }

        $task = $this->task($user, $reminder->getSubjectId());
        if (null === $task || $task->isDone()) {
            return null;
        }

        switch ($reminder->getKind()) {
            case TaskReminder::KIND_DUE:
                // Moved or no longer at that time since it was planned: it has nothing left to say.
                $zone = $local->getTimezone();
                $delay = array_search($reminder->getScheduledFor()->setTimezone($zone)->format('Y-m-d H:i'), array_map(
                    static fn (\DateTimeImmutable $at): string => $at->format('Y-m-d H:i'),
                    self::timedAt($task, $zone),
                ), true);
                if (false === $delay) {
                    return null;
                }
                $time = (string) $task->getScheduledTime();

                return [
                    'type' => NotificationType::TaskDue,
                    'key' => 'due.timed',
                    'title' => mb_strimwidth($task->getTitle(), 0, ReminderMessages::TITLE_MAX, '…'),
                    'body' => 0 === $delay ? 'C\'est l\'heure · '.$time : 'Dans '.self::delay($delay).' · '.$time,
                    'actions' => [TaskReminder::ACTION_START, TaskReminder::ACTION_DONE, TaskReminder::ACTION_TOMORROW],
                    'iri' => '/api/tasks/'.$reminder->getSubjectId(),
                ];
            case TaskReminder::KIND_NUDGE:
                $drawn = $this->messages->nudge($settings->getTone(), $task->getTitle(), $previous);
                $firstStep = $task->getFirstStep();

                return [
                    'type' => NotificationType::TaskNudge,
                    'key' => $drawn['key'],
                    'title' => $drawn['message'],
                    'body' => null === $firstStep || '' === $firstStep ? null : 'Premier pas : '.$firstStep,
                    'actions' => [
                        TaskReminder::ACTION_START,
                        TaskReminder::ACTION_IN10,
                        $this->tonightAhead($settings, $local) ? TaskReminder::ACTION_TONIGHT : TaskReminder::ACTION_TOMORROW,
                    ],
                    'iri' => '/api/tasks/'.$reminder->getSubjectId(),
                ];
            default:
                return null;
        }
    }

    /**
     * The routines of a moment still to tick, in one notification; null when
     * all are ticked meanwhile, or none asks to be reminded anymore.
     *
     * @return Composed|null
     */
    private function composeRoutine(TaskReminder $reminder, TaskSettings $settings, ?string $previous): ?array
    {
        [$day, $key] = RoutineReminders::parse($reminder->getSubjectId());
        $group = $this->routineReminders->group($reminder->getUser(), $day, $key);
        if (null === $group) {
            return null;
        }
        $titles = array_map(static fn (Routine $routine): string => $routine->getTitle(), $group['routines']);
        $drawn = $this->messages->routine($settings->getTone(), $titles, $previous);

        return [
            'type' => NotificationType::Routine,
            'key' => $drawn['key'],
            'title' => 'Routines · '.RoutineReminders::dayPart($group['time']),
            'body' => $drawn['message'],
            'actions' => 1 === \count($titles)
                ? [TaskReminder::ACTION_DONE, TaskReminder::ACTION_IN10, TaskReminder::ACTION_OPEN]
                : [TaskReminder::ACTION_OPEN, TaskReminder::ACTION_IN10],
            'iri' => '/tasks',
        ];
    }

    /** @param Composed $composed */
    private function notify(User $user, TaskReminder $reminder, array $composed): ?Notification
    {
        // Null when the user turned notifications off.
        $notification = $this->bus->dispatch(new CreateNotificationCommand(
            type: $composed['type']->value,
            title: $composed['title'],
            body: $composed['body'],
            relatedEntityIri: $composed['iri'],
            userId: (string) $user->getId(),
            reminderId: (string) $reminder->getId(),
            actions: $composed['actions'],
        ))->last(HandledStamp::class)?->getResult();

        return $notification instanceof Notification ? $notification : null;
    }

    private function plan(User $user, string $kind, string $subjectId, ?DayMoment $moment, \DateTimeImmutable $at, bool $snoozed = false): TaskReminder
    {
        $reminder = (new TaskReminder())
            ->setUser($user)
            ->setKind($kind)
            ->setSubjectId($subjectId)
            ->setMoment($moment)
            ->setScheduledFor($at)
            ->setSnoozed($snoozed);
        $this->em->persist($reminder);

        return $reminder;
    }

    /**
     * « Demain », as the evening does it (MAG-500): at the end of tomorrow's
     * five, which stays a proposal. Pressed during the day, the task also
     * leaves today's five, or it would stay « now » and be nudged again.
     */
    private function putInTomorrow(User $user, Task $task): void
    {
        $today = $this->overdue->today($user);
        $taskId = (string) $task->getId();

        $tomorrow = $this->dayPlans->forDate($user, $today->modify('+1 day'));
        $todayPlan = $this->dayPlanRepository->findOneByUserAndDate($user, $today);
        $toTomorrow = !\in_array($taskId, $tomorrow->getTaskIds(), true);
        if ($toTomorrow && \count($tomorrow->getSlots()) >= DayPlan::MAX_SLOTS) {
            throw new \DomainException(\sprintf('Tomorrow already holds %d tasks.', DayPlan::MAX_SLOTS));
        }

        // Both days or neither: the answer runs in one transaction ({@see AnswerTaskReminderHandler}).
        if ($toTomorrow) {
            $this->setSlots($user, $tomorrow, [...self::slotsOf($tomorrow), ['type' => DayPlan::TYPE_TASK, 'taskId' => $taskId]]);
        }
        if (null !== $todayPlan && \in_array($taskId, $todayPlan->getTaskIds(), true)) {
            $this->setSlots($user, $todayPlan, array_values(array_filter(self::slotsOf($todayPlan), static fn (array $slot): bool => $slot['taskId'] !== $taskId)));
        }
    }

    /** @return list<array{type: string, taskId: string}> */
    private static function slotsOf(DayPlan $plan): array
    {
        return array_map(static fn (array $slot): array => ['type' => $slot['type'], 'taskId' => $slot['taskId']], $plan->getSlots());
    }

    /** @param list<array{type: string, taskId: string}> $slots */
    private function setSlots(User $user, DayPlan $plan, array $slots): void
    {
        try {
            // Written without settling the day: tomorrow stays a proposal, today is already settled.
            $this->bus->dispatch(new SetDayPlanCommand((string) $user->getId(), (string) $plan->getId(), $slots, confirm: false));
        } catch (HandlerFailedException $e) {
            // A day refused by its own rules is the caller's to read; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            throw $cause instanceof \DomainException ? $cause : $e;
        }
    }

    /** Whether « Ce soir » is still ahead: `afterDinner` to come, and not in the quiet hours. */
    private function tonightAhead(TaskSettings $settings, \DateTimeImmutable $local): bool
    {
        $time = $settings->getDayMoment(DayMoment::AfterDinner);

        return self::at($local, $time) > $local && !$settings->isQuiet($time);
    }

    private function task(User $user, string $id): ?Task
    {
        return $this->tasks->findOwnedByIds($user, [$id])[$id] ?? null;
    }

    /** Read, not created: no settings means the defaults. */
    private function settingsOf(User $user): TaskSettings
    {
        return $this->settings->findOneByUser($user) ?? (new TaskSettings())->setUser($user);
    }

    private function local(User $user): \DateTimeImmutable
    {
        return $this->clock->now()->setTimezone($this->overdue->zone($user));
    }

    /** « 30 min », « 1 h », « 1 h 30 », « 2 j » : a delay before the time, as said. */
    private static function delay(int $minutes): string
    {
        if ($minutes < 60) {
            return $minutes.' min';
        }
        if (0 === $minutes % 1440) {
            return intdiv($minutes, 1440).' j';
        }
        $hours = intdiv($minutes, 60);
        $rest = $minutes % 60;

        return 0 === $rest ? $hours.' h' : \sprintf('%d h %02d', $hours, $rest);
    }

    /** $time, `HH:MM`, on the day of $local and in its zone. */
    private static function at(\DateTimeImmutable $local, string $time): \DateTimeImmutable
    {
        [$hours, $minutes] = array_map('intval', explode(':', $time));

        return $local->setTime($hours, $minutes);
    }

    /** Whether $local is at $at or within the grace after it. */
    private static function within(\DateTimeImmutable $local, \DateTimeImmutable $at): bool
    {
        return $local >= $at && $local < $at->modify(self::GRACE);
    }

    /** @return array{\DateTimeImmutable, \DateTimeImmutable} the local day of $local, [midnight, next midnight[ */
    private static function dayOf(\DateTimeImmutable $local): array
    {
        $start = $local->setTime(0, 0);

        return [$start, $start->modify('+1 day')];
    }
}
