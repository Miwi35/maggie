<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\ReleaseMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\UseCase\ReleaseMealGroceries;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ReleaseMealGroceriesHandler
{
    public function __construct(
        private readonly ReleaseMealGroceries $releaseMealGroceries,
        private readonly MealRepository $mealRepository,
    ) {
    }

    public function __invoke(ReleaseMealGroceriesCommand $command): ?GroceryList
    {
        // Gone again before the event was handled: nothing left to release.
        $meal = $this->mealRepository->find($command->mealId);

        return null === $meal ? null : $this->releaseMealGroceries->execute($meal);
    }
}
