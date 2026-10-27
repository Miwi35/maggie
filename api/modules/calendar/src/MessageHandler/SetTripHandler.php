<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Exception\InvalidTripException;
use Maggie\Calendar\Message\SetTripCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\SetTrip;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Uid\Ulid;

/**
 * Refusals are {@see InvalidTripException}s naming the field, Google failing a
 * {@see \Maggie\Calendar\Routing\RoutingException}: the caller's to read.
 */
#[AsMessageHandler]
class SetTripHandler
{
    public function __construct(
        private readonly EventRepository $events,
        private readonly SetTrip $setTrip,
    ) {
    }

    public function __invoke(SetTripCommand $command): Trip
    {
        $event = Ulid::isValid($command->eventId) ? $this->events->find($command->eventId) : null;
        // Another user's event answers exactly like a missing one.
        if (!$event instanceof Event || (string) $event->getAgenda()->getUser()->getId() !== $command->userId) {
            throw new InvalidTripException('eventId', "no event of yours has the id {$command->eventId}.");
        }

        return $this->setTrip->execute($event, $command->mode, $command->origin, $command->decidedBy);
    }
}
