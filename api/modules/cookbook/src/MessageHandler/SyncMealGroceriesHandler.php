<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\SyncMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\UseCase\SyncMealGroceries;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class SyncMealGroceriesHandler
{
    public function __construct(
        private readonly SyncMealGroceries $syncMealGroceries,
        private readonly MealRepository $mealRepository,
    ) {
    }

    public function __invoke(SyncMealGroceriesCommand $command): ?GroceryList
    {
        // Gone again before the event was handled: nothing left to follow.
        $meal = $this->mealRepository->find($command->mealId);

        return null === $meal ? null : $this->syncMealGroceries->execute($meal);
    }
}
