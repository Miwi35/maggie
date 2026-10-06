<?php

declare(strict_types=1);

namespace Maggie\Notification\Command;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:notification:check-reminders',
    description: 'Check for due event reminders and create notifications',
)]
class CheckRemindersCommand extends Command
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly MessageBusInterface $messageBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = new \DateTimeImmutable();
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
            $occurrenceStart = $occurrence->getStartAt();

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
                if ($occurrence->getStartAt() <= $now || $occurrence->getStartAt() >= $horizon) {
                    continue;
                }

                // An overridden occurrence is reached twice — as the row it is, and
                // through the series it belongs to. Keyed so it is looked at once.
                $candidates[$occurrence->getId().'@'.$occurrence->getStartAt()->getTimestamp()] = $occurrence;
            }
        }

        return array_values($candidates);
    }
}
