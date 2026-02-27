<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteStoreCommand;
use Maggie\Cookbook\Repository\StoreRepository;
use Maggie\Cookbook\UseCase\DeleteStore;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteStoreHandler
{
    public function __construct(
        private readonly DeleteStore $deleteStore,
        private readonly StoreRepository $storeRepository,
    ) {
    }

    public function __invoke(DeleteStoreCommand $command): void
    {
        $store = $this->storeRepository->find($command->storeId)
            ?? throw new \DomainException("Store not found: {$command->storeId}");

        $this->deleteStore->execute($store);
    }
}
