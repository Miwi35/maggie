<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Agenda, Agenda> */
class UpdateAgendaProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Agenda
    {
        // This processor sends every field: a nullable one that is null is an explicit clear
        $clearFields = [];
        if ($data->getDescription() === null) {
            $clearFields[] = 'description';
        }
        if ($data->getColor() === null) {
            $clearFields[] = 'color';
        }

        $envelope = $this->bus->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            timeZone: $data->getTimeZone(),
            color: $data->getColor(),
            isDefault: $data->isDefault(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
