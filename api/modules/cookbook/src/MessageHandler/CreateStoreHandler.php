<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Store;
use Maggie\Cookbook\Message\CreateStoreCommand;
use Maggie\Cookbook\UseCase\CreateStore;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateStoreHandler
{
    public function __construct(
        private readonly CreateStore $createStore,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateStoreCommand $command): Store
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $store = new Store();
        $store->setUser($user);
        $store->setName($command->name);
        $store->setVisitOrder($command->visitOrder);

        if ($command->description !== null) {
            $store->setDescription($command->description);
        }

        return $this->createStore->execute($store);
    }
}
