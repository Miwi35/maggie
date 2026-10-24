<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\MessageHandler;

use Maggie\Grocery\Entity\GroceryList;
use Maggie\MealGrocery\Message\RevokeMealGroceriesCommand;
use Maggie\MealGrocery\UseCase\RevokeMealGroceries;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RevokeMealGroceriesHandler
{
    public function __construct(
        private readonly RevokeMealGroceries $revokeMealGroceries,
    ) {
    }

    public function __invoke(RevokeMealGroceriesCommand $command): ?GroceryList
    {
        return $this->revokeMealGroceries->execute($command->contributions);
    }
}
