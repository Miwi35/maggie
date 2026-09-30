<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Symfony\Component\HttpFoundation\RequestStack;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Agenda, void> */
class DeleteAgendaProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RequestStack $requestStack,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $deleteGoogleCalendar = false;
        $request = $this->requestStack->getCurrentRequest();
        if (null !== $request) {
            $deleteGoogleCalendar = filter_var(
                $request->query->get('deleteGoogleCalendar', 'false'),
                \FILTER_VALIDATE_BOOLEAN,
            );
        }

        $this->bus->dispatch(new DeleteAgendaCommand(
            agendaId: (string) $data->getId(),
            deleteGoogleCalendar: $deleteGoogleCalendar,
        ));
    }
}
