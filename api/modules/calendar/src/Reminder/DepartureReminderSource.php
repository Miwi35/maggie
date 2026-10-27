<?php

declare(strict_types=1);

namespace Maggie\Calendar\Reminder;

use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « Pars maintenant » (MAG-655): the alert of a trip whose `departAt` has come
 * and whose event has not started. Maggie plans, the API alerts — it fires from
 * the cron even when no check of the agent ran.
 *
 * One alert per value of `departAt`, filed against the trip with `departAt` as its
 * occurrence: a trip moved by `set_trip` alerts again, the same value never twice.
 */
#[AsTaggedItem(priority: 25)]
final class DepartureReminderSource implements ReminderSourceInterface
{
    public function __construct(
        private readonly TripRepository $tripRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];

        $tripsByUser = [];
        foreach ($this->tripRepository->findDepartureDue($now) as $trip) {
            $tripsByUser[(string) $trip->getEvent()->getAgenda()->getUser()->getId()][] = $trip;
        }

        foreach ($tripsByUser as $userId => $trips) {
            try {
                foreach ($trips as $trip) {
                    if ($this->alert($trip, $userId)) {
                        ++$created;
                        $lines[] = \sprintf(
                            '  Created departure alert for "%s" (leave at %s)',
                            $trip->getEvent()->getSummary(),
                            $trip->getDepartAt()->format('c'),
                        );
                    }
                }
            } catch (\Throwable $e) {
                // One user's trouble must not cost the others their alert.
                $this->logger->error('Departure alerts failed for {user}: {error}', ['user' => $userId, 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Departure alerts failed for user %s: %s', $userId, $e->getMessage());
            }
        }

        return new ReminderReport($created, $lines, $warnings);
    }

    private function alert(Trip $trip, string $userId): bool
    {
        $tripIri = '/api/trips/'.$trip->getId();

        if ($this->notificationRepository->departureExists($tripIri, $trip->getDepartAt())) {
            return false;
        }

        $this->messageBus->dispatch(new CreateNotificationCommand(
            type: 'departure',
            title: 'Pars maintenant pour '.$trip->getEvent()->getSummary(),
            relatedEntityIri: $tripIri,
            userId: $userId,
            occurrenceStartAt: $trip->getDepartAt(),
        ));

        return true;
    }
}
