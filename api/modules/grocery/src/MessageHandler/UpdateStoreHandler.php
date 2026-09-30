<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\UpdateStoreCommand;
use Maggie\Grocery\Repository\StoreRepository;
use Maggie\Grocery\UseCase\UpdateStore;
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

        if (null !== $command->name) {
            $store->setName($command->name);
        }
        if (null !== $command->description) {
            $store->setDescription($command->description);
        } elseif ($command->clears('description')) {
            $store->setDescription(null);
        }
        if (null !== $command->visitOrder) {
            $store->setVisitOrder($command->visitOrder);
        }

        return $this->updateStore->execute($store);
    }
}
