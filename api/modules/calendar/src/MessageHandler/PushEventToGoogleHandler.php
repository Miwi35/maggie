<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class PushEventToGoogleHandler
{
    public function __construct(
        private readonly GoogleCalendarSyncService $syncService,
        private readonly EventRepository $eventRepository,
    ) {
    }

    public function __invoke(PushEventToGoogleCommand $command): void
    {
        $event = $this->eventRepository->find($command->eventId);
        if ($event === null) {
            return;
        }

        $this->syncService->pushEventToGoogle($event, $command->action);
    }
}
