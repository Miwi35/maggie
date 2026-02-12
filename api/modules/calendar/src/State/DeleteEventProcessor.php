<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\DeleteEventCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Event, void> */
class DeleteEventProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteEventCommand(
            eventId: (string) $data->getId(),
        ));
    }
}
