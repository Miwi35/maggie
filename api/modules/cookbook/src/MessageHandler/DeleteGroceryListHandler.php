<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteGroceryListCommand;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\UseCase\DeleteGroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteGroceryListHandler
{
    public function __construct(
        private readonly DeleteGroceryList $deleteGroceryList,
        private readonly GroceryListRepository $groceryListRepository,
    ) {
    }

    public function __invoke(DeleteGroceryListCommand $command): void
    {
        $list = $this->groceryListRepository->find($command->groceryListId)
            ?? throw new \DomainException("Grocery list not found: {$command->groceryListId}");

        $this->deleteGroceryList->execute($list);
    }
}
