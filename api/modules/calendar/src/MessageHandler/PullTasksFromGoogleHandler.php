<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Calendar\Service\GoogleTasksSyncService;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class PullTasksFromGoogleHandler
{
    public function __construct(
        private readonly GoogleTasksSyncService $syncService,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(PullTasksFromGoogleCommand $command): void
    {
        $user = $this->userRepository->find($command->userId);
        if ($user === null) {
            return;
        }

        $this->syncService->pullFromGoogle($user);
    }
}
