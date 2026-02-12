<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Agenda, Agenda> */
class CreateAgendaProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Agenda
    {
        $envelope = $this->bus->dispatch(new CreateAgendaCommand(
            name: $data->getName(),
            description: $data->getDescription(),
            timeZone: $data->getTimeZone(),
            color: $data->getColor(),
            isDefault: $data->isDefault(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
