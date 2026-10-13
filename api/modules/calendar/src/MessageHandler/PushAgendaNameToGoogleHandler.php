<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\PushAgendaNameToGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\UseCase\PushAgendaNameToGoogle;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class PushAgendaNameToGoogleHandler
{
    public function __construct(
        private readonly PushAgendaNameToGoogle $pushAgendaNameToGoogle,
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(PushAgendaNameToGoogleCommand $command): void
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if (null === $agenda) {
            return;
        }

        $this->pushAgendaNameToGoogle->execute($agenda, $command->previousName);
    }
}
