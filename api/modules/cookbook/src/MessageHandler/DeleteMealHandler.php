<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\UseCase\DeleteMeal;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteMealHandler
{
    public function __construct(
        private readonly DeleteMeal $deleteMeal,
        private readonly MealRepository $mealRepository,
        private readonly MealGrocerySync $mealGrocerySync,
    ) {
    }

    public function __invoke(DeleteMealCommand $command): void
    {
        $meal = $this->mealRepository->find($command->mealId)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        // A cancelled dinner is shopping nobody has to do. Not flushed here:
        // `DeleteMeal` commits, so the ingredients leave the list and the meal
        // leaves the agenda in one transaction — a delete that fails must not
        // leave the shopping already gone.
        $this->mealGrocerySync->revoke($meal);

        $this->deleteMeal->execute($meal);
    }
}
