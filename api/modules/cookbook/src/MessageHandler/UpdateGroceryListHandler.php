<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryListStatus;
use Maggie\Cookbook\Message\UpdateGroceryListCommand;
use Maggie\Cookbook\Repository\GroceryListRepository;
use Maggie\Cookbook\UseCase\UpdateGroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateGroceryListHandler
{
    public function __construct(
        private readonly UpdateGroceryList $updateGroceryList,
        private readonly GroceryListRepository $groceryListRepository,
    ) {
    }

    public function __invoke(UpdateGroceryListCommand $command): GroceryList
    {
        $list = $this->groceryListRepository->find($command->groceryListId)
            ?? throw new \DomainException("Grocery list not found: {$command->groceryListId}");

        if ($command->status !== null) {
            $list->setStatus(GroceryListStatus::from($command->status));
        }

        $list->setUpdatedAt(new \DateTimeImmutable());

        return $this->updateGroceryList->execute($list);
    }
}
