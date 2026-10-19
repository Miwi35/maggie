<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\DayMoment;
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
 * - `due`: the morning alerts of {@see TaskAlertSchedule}, at the morning or,
 *   when it falls in the quiet hours, at their end;
 * - `nudge`: at `afterBreakfast` and `afterLunch`, the task « now » of a
 *   confirmed day, once per moment and per day.
 *
 * The rules hold for every kind: nothing in the quiet hours — a reminder that
 * falls in them is dropped, never piled up for the morning —, at most
 * `reminderDailyCap` a local day (those the user put off aside), and never the
 * message of the previous reminder of the same kind. A new kind plugs in at
 * {@see self::compose()}.
 *
 * @phpstan-type Composed array{type: NotificationType, key: string, title: string, body: string|null, actions: list<string>}
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
        private readonly TaskAlertSchedule $alertSchedule,
        private readonly TaskOverdue $overdue,
        private readonly DayPlanRepository $dayPlanRepository,
        private readonly DayPlans $dayPlans,
        private readonly TaskRepository $tasks,
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

        $this->planDue($user, $settings, $local);
        $this->planNudges($user, $settings, $local);
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
        $task = $this->task($user, $reminder->getSubjectId())
            ?? throw new \DomainException('The task of this reminder no longer exists.');
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
                if (!$this->tonightAhead($settings, $local)) {
                    throw new \DomainException('Tonight has passed: put it in tomorrow instead.');
                }
                $next = $this->plan($user, $reminder->getKind(), $reminder->getSubjectId(), DayMoment::AfterDinner, self::at($local, $settings->getDayMoment(DayMoment::AfterDinner)), true);
                break;
            case TaskReminder::ACTION_TOMORROW:
                $this->putInTomorrow($user, $task);
                break;
            case TaskReminder::ACTION_DONE:
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

    private function planDue(User $user, TaskSettings $settings, \DateTimeImmutable $local): void
    {
        $at = self::at($local, $settings->getAlertTime());
        if (!self::within($local, $at)) {
            return;
        }

        [$from, $to] = self::dayOf($local);
        foreach ($this->alertSchedule->dueAlerts($user, $this->overdue->today($user)) as $alert) {
            $taskId = (string) $alert->task->getId();
            if (!$this->reminders->isPlanned($user, TaskReminder::KIND_DUE, $from, $to, subjectId: $taskId)) {
                $this->plan($user, TaskReminder::KIND_DUE, $taskId, null, $at);
            }
        }
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

    /** The rules every kind keeps: on time, outside the quiet hours, under the daily cap unless put off by the user. */
    private function mayGo(TaskReminder $reminder, TaskSettings $settings, \DateTimeImmutable $local, \DateTimeImmutable $from, \DateTimeImmutable $to): bool
    {
        // Missed, it is not sent late: never piled up after the quiet hours or a restart.
        if ($local >= $reminder->getScheduledFor()->modify(self::GRACE) || $settings->isQuiet($local->format('H:i'))) {
            return false;
        }

        return $reminder->isSnoozed() || $this->reminders->countCappedSentBetween($reminder->getUser(), $from, $to) < $settings->getReminderDailyCap();
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
        $task = $this->task($user, $reminder->getSubjectId());
        if (null === $task || $task->isDone()) {
            return null;
        }
        $previous = $this->reminders->findLastMessageKey($user, $reminder->getKind());

        switch ($reminder->getKind()) {
            case TaskReminder::KIND_DUE:
                $alert = $this->alertSchedule->alertFor($task, $this->overdue->today($user), $settings->getAlertSchedule());
                if (null === $alert) {
                    return null;
                }
                $drawn = $this->messages->due($settings->getTone(), $alert->reason, $alert->days, $task->getTitle(), $previous);

                return [
                    'type' => NotificationType::TaskDue,
                    'key' => $drawn['key'],
                    'title' => $drawn['message'],
                    'body' => null,
                    'actions' => [TaskReminder::ACTION_START, TaskReminder::ACTION_DONE, TaskReminder::ACTION_TOMORROW],
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
                ];
            default:
                // `routine` is the Routines lot's.
                return null;
        }
    }

    /** @param Composed $composed */
    private function notify(User $user, TaskReminder $reminder, array $composed): ?Notification
    {
        // Null when the user turned notifications off.
        $notification = $this->bus->dispatch(new CreateNotificationCommand(
            type: $composed['type']->value,
            title: $composed['title'],
            body: $composed['body'],
            relatedEntityIri: '/api/tasks/'.$reminder->getSubjectId(),
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
