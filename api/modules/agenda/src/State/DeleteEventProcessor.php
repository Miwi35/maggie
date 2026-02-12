<?php

namespace Maggie\Agenda\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\DeleteEventCommand;
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
