<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\UseCase\DeleteAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteAgendaHandler
{
    public function __construct(
        private readonly DeleteAgenda $deleteAgenda,
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(DeleteAgendaCommand $command): void
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if ($agenda === null) {
            throw new \DomainException("Agenda not found: {$command->agendaId}");
        }

        $this->deleteAgenda->execute($agenda);
    }
}
