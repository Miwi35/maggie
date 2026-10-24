<?php

declare(strict_types=1);

namespace Maggie\Calendar\Reminder;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\RecurrenceService;
use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;
use Symfony\Component\Messenger\MessageBusInterface;

/** The reminders an event carries, for the next 24 hours. */
#[AsTaggedItem(priority: 30)]
final class EventReminderSource implements ReminderSourceInterface
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly MessageBusInterface $messageBus,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $horizon = $now->modify('+24 hours');

        $created = 0;
        $lines = [];

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
                $lines[] = \sprintf(
                    '  Created reminder for "%s" of %s (%d min before)',
                    $occurrence->getSummary(),
                    $occurrenceStart->format('c'),
                    $minutes,
                );
            }
        }

        return new ReminderReport($created, $lines);
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
}
