<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\UseCase\DeleteMeal;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteMealHandler
{
    public function __construct(
        private readonly DeleteMeal $deleteMeal,
        private readonly MealRepository $mealRepository,
    ) {
    }

    public function __invoke(DeleteMealCommand $command): void
    {
        $meal = $this->mealRepository->find($command->mealId)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        $this->deleteMeal->execute($meal);
    }
}
