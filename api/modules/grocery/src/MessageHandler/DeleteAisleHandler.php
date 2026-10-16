<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Message\DeleteAisleCommand;
use Maggie\Grocery\Service\OwnedAisles;
use Maggie\Grocery\UseCase\DeleteAisle;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteAisleHandler
{
    public function __construct(
        private readonly DeleteAisle $deleteAisle,
        private readonly OwnedAisles $ownedAisles,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(DeleteAisleCommand $command): void
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $this->deleteAisle->execute($this->ownedAisles->aisle($user, $command->aisleId));
    }
}
