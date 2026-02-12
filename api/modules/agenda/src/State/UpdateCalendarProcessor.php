<?php

namespace Maggie\Agenda\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Message\UpdateCalendarCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Calendar, Calendar> */
class UpdateCalendarProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Calendar
    {
        $envelope = $this->bus->dispatch(new UpdateCalendarCommand(
            calendarId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            timeZone: $data->getTimeZone(),
            color: $data->getColor(),
            isDefault: $data->isDefault(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
