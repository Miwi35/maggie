<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\RevokeMealGroceriesCommand;
use Maggie\Cookbook\UseCase\RevokeMealGroceries;
use Maggie\Grocery\Entity\GroceryList;
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
