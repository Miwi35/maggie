<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\UpdateAisleCommand;
use Maggie\Grocery\Service\OwnedAisles;
use Maggie\Grocery\UseCase\UpdateAisle;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateAisleHandler
{
    public function __construct(
        private readonly UpdateAisle $updateAisle,
        private readonly OwnedAisles $ownedAisles,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(UpdateAisleCommand $command): Aisle
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $aisle = $this->ownedAisles->aisle($user, $command->aisleId);

        return $this->updateAisle->execute($aisle, $command->name, $command->position);
    }
}
