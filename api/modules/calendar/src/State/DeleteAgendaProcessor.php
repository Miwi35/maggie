<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Agenda, void> */
class DeleteAgendaProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteAgendaCommand(
            agendaId: (string) $data->getId(),
        ));
    }
}
