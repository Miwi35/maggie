<?php

namespace Maggie\Agenda\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Message\DeleteCalendarCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Calendar, void> */
class DeleteCalendarProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteCalendarCommand(
            calendarId: (string) $data->getId(),
        ));
    }
}
