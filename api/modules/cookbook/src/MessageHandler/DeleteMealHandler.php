<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Cookbook\UseCase\DeleteMeal;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteMealHandler
{
    public function __construct(
        private readonly DeleteMeal $deleteMeal,
        private readonly MealRepository $mealRepository,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly GroceryListBroadcaster $groceryListBroadcaster,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(DeleteMealCommand $command): void
    {
        $meal = $this->mealRepository->find($command->mealId)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        // Before the meal goes: a cancelled dinner is shopping nobody has to do.
        $list = $this->mealGrocerySync->revoke($meal);
        $this->em->flush();

        $this->deleteMeal->execute($meal);

        $this->groceryListBroadcaster->broadcast($list);
    }
}
