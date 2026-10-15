<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\RescaleMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGuests;
use Maggie\Cookbook\UseCase\RescaleMealGroceries;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RescaleMealGroceriesHandler
{
    public function __construct(
        private readonly RescaleMealGroceries $rescaleMealGroceries,
        private readonly MealRepository $mealRepository,
        private readonly MealGuests $mealGuests,
    ) {
    }

    public function __invoke(RescaleMealGroceriesCommand $command): ?GroceryList
    {
        // Gone again before the event was handled: nothing left to scale.
        $meal = $this->mealRepository->find($command->mealId);
        if (null === $meal) {
            return null;
        }

        $previous = $command->previousGuests ?? $this->mealGuests->defaultFor($meal->getAgenda()->getUser());

        return $this->rescaleMealGroceries->execute($meal, $previous);
    }
}
