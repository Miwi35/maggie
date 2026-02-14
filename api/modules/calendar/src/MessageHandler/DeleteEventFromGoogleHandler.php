<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteEventFromGoogleHandler
{
    public function __construct(
        private readonly GoogleCalendarSyncService $syncService,
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(DeleteEventFromGoogleCommand $command): void
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if ($agenda === null) {
            return;
        }

        $this->syncService->deleteEventFromGoogle($agenda, $command->googleEventId);
    }
}
