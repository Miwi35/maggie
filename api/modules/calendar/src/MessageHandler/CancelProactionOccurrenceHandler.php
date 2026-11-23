<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\CancelProactionOccurrenceCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CancelProactionOccurrenceHandler
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CancelProactionOccurrenceCommand $command): void
    {
        $user = $this->userRepository->find($command->userId) ?? throw new \DomainException('No user found.');

        $this->proactions->cancelOccurrence($user, $command->seriesId, $command->occurrence);
    }
}
