<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Store;
use Maggie\Cookbook\Message\UpdateStoreCommand;
use Maggie\Cookbook\Repository\StoreRepository;
use Maggie\Cookbook\UseCase\UpdateStore;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateStoreHandler
{
    public function __construct(
        private readonly UpdateStore $updateStore,
        private readonly StoreRepository $storeRepository,
    ) {
    }

    public function __invoke(UpdateStoreCommand $command): Store
    {
        $store = $this->storeRepository->find($command->storeId)
            ?? throw new \DomainException("Store not found: {$command->storeId}");

        if ($command->name !== null) {
            $store->setName($command->name);
        }
        if ($command->description !== null) {
            $store->setDescription($command->description);
        }
        if ($command->visitOrder !== null) {
            $store->setVisitOrder($command->visitOrder);
        }

        return $this->updateStore->execute($store);
    }
}
