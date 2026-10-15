<?php

declare(strict_types=1);

namespace Maggie\Notification\Command;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\TaskAlertReason;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Calendar\Service\TaskAlert;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:notification:check-reminders',
    description: 'Check for due event reminders and task alerts and create notifications',
)]
class CheckRemindersCommand extends Command
{
    /**
     * How long after a user's morning the task alerts still go out. The cron runs
     * every minute; a minute lost to a deploy or a restart must not cost the
     * day's alerts. The run is told apart from the next by the dedup key, not by
     * the clock.
     */
    private const MORNING_GRACE = '+10 minutes';

    private const TITLE_MAX = 255;

    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly MessageBusInterface $messageBus,
        private readonly UserRepository $userRepository,
        private readonly TaskSettingsRepository $taskSettingsRepository,
        private readonly TaskAlertSchedule $taskAlertSchedule,
        private readonly TaskOverdue $taskOverdue,
        private readonly ClockInterface $clock,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = $this->clock->now();
        $horizon = $now->modify('+24 hours');

        $created = 0;

        foreach ($this->occurrencesInWindow($now, $horizon) as $occurrence) {
            $reminders = $occurrence->getReminders();
            if (null === $reminders) {
                continue;
            }

            $overrides = $reminders['overrides'] ?? [];
            if (!\is_array($overrides) || [] === $overrides) {
                continue;
            }

            // A virtual occurrence is a clone of its master, so this is the series'
            // own IRI: the owner opens the event, and the occurrence is what the
            // notification carries beside it.
            $eventIri = '/api/events/'.$occurrence->getId();
            $occurrenceStart = $occurrence->getStartInstant();

            foreach ($overrides as $override) {
                $minutes = \is_array($override) ? (int) ($override['minutes'] ?? 0) : 0;
                if ($minutes <= 0) {
                    continue;
                }

                $triggerTime = $occurrenceStart->modify("-{$minutes} minutes");

                if ($triggerTime > $now) {
                    continue;
                }

                if ($this->notificationRepository->reminderExists($eventIri, $minutes, $occurrenceStart)) {
                    continue;
                }

                $userId = (string) $occurrence->getAgenda()->getUser()->getId();

                $this->messageBus->dispatch(new CreateNotificationCommand(
                    type: 'reminder',
                    title: $occurrence->getSummary(),
                    body: (string) $minutes,
                    relatedEntityIri: $eventIri,
                    userId: $userId,
                    occurrenceStartAt: $occurrenceStart,
                ));

                ++$created;
                $io->writeln(sprintf(
                    '  Created reminder for "%s" of %s (%d min before)',
                    $occurrence->getSummary(),
                    $occurrenceStart->format('c'),
                    $minutes,
                ));
            }
        }

        $created += $this->alertTasks($now, $io);

        if (0 === $created) {
            $io->info('No due reminders found.');
        } else {
            $io->success(sprintf('Created %d reminder notification(s).', $created));
        }

        return Command::SUCCESS;
    }

    /**
     * Every occurrence starting inside the window — the things a reminder can be about.
     *
     * A recurring event is one row whose `startAt` is its first occurrence, so
     * reading the rows alone reminded the owner of the first standup and of
     * nothing after it (MAG-121). The masters are expanded here instead, over the
     * same window.
     *
     * Two guards keep the list honest. An occurrence that already started is
     * dropped: a reminder sent after the start is noise, and that is what used to
     * silence every recurring master once its first occurrence had passed. And an
     * occurrence beyond the horizon is dropped too — an overridden occurrence comes
     * back from its series wherever the owner moved it, which may be next month.
     *
     * @return list<Event>
     */
    private function occurrencesInWindow(\DateTimeImmutable $now, \DateTimeImmutable $horizon): array
    {
        $candidates = [];

        foreach ($this->eventRepository->findByDateRangeForAllUsers($now, $horizon) as $event) {
            $occurrences = $event->isRecurring()
                ? $this->recurrenceService->expandOccurrences($event, $now, $horizon)
                : [$event];

            foreach ($occurrences as $occurrence) {
                if ($occurrence->getStartInstant() <= $now || $occurrence->getStartInstant() >= $horizon) {
                    continue;
                }

                // An overridden occurrence is reached twice — as the row it is, and
                // through the series it belongs to. Keyed so it is looked at once.
                $candidates[$occurrence->getId().'@'.$occurrence->getStartInstant()->getTimestamp()] = $occurrence;
            }
        }

        return array_values($candidates);
    }

    /**
     * The task alerts of every user whose morning it is (MAG-410): one `task_due`
     * notification per task the alert schedule picks, once per task and per day —
     * the key is the task and the start of the user's day, so a second run, a
     * changed morning time or a task edited since does not say it twice.
     */
    private function alertTasks(\DateTimeImmutable $now, SymfonyStyle $io): int
    {
        $created = 0;

        foreach ($this->userRepository->findAll() as $user) {
            $local = $now->setTimezone($this->taskOverdue->zone($user));
            if (!$this->isMorning($user, $local)) {
                continue;
            }

            $dayStart = $local->setTime(0, 0);

            foreach ($this->taskAlertSchedule->dueAlerts($user, $this->taskOverdue->today($user)) as $alert) {
                $iri = '/api/tasks/'.$alert->task->getId();
                if ($this->notificationRepository->taskAlertExists($iri, $dayStart)) {
                    continue;
                }

                $title = self::titleOf($alert);
                $this->messageBus->dispatch(new CreateNotificationCommand(
                    type: 'task_due',
                    title: $title,
                    relatedEntityIri: $iri,
                    userId: (string) $user->getId(),
                    occurrenceStartAt: $dayStart,
                ));

                ++$created;
                $io->writeln(sprintf('  Created task alert "%s" for %s', $title, $user->getEmail()));
            }
        }

        return $created;
    }

    /** Whether $local, the user's own clock, is within the grace period after their morning time. */
    private function isMorning(User $user, \DateTimeImmutable $local): bool
    {
        $time = $this->taskSettingsRepository->findOneByUser($user)?->getMorningTime() ?? TaskSettings::DEFAULT_MORNING_TIME;
        [$hours, $minutes] = array_map('intval', explode(':', $time));
        $morning = $local->setTime($hours, $minutes);

        return $local >= $morning && $local < $morning->modify(self::MORNING_GRACE);
    }

    private static function titleOf(TaskAlert $alert): string
    {
        $prefix = match ($alert->reason) {
            TaskAlertReason::DaysBefore => sprintf('Dans %d j : ', $alert->days),
            TaskAlertReason::DueDay => "Aujourd'hui : ",
            TaskAlertReason::Overdue => sprintf('En retard de %d j : ', $alert->days),
        };

        return $prefix.mb_strimwidth($alert->task->getTitle(), 0, self::TITLE_MAX - mb_strlen($prefix), '…');
    }
}
