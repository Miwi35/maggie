<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\CreateAisleCommand;
use Maggie\Grocery\Service\OwnedAisles;
use Maggie\Grocery\UseCase\CreateAisle;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateAisleHandler
{
    public function __construct(
        private readonly CreateAisle $createAisle,
        private readonly OwnedAisles $ownedAisles,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateAisleCommand $command): Aisle
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $store = $this->ownedAisles->store($user, $command->storeId);

        return $this->createAisle->execute($user, $store, $command->name, $command->position);
    }
}
