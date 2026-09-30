<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\PullFromGoogleCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class PullFromGoogleHandler
{
    public function __construct(
        private readonly GoogleCalendarSyncService $syncService,
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(PullFromGoogleCommand $command): void
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if (null === $agenda) {
            return;
        }

        $this->syncService->pullFromGoogle($agenda);
    }
}
