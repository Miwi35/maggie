<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\ConsolidateGroceryListCommand;
use Maggie\Cookbook\Service\GroceryLineConsolidator;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ConsolidateGroceryListHandler
{
    public function __construct(
        private readonly GroceryLineConsolidator $consolidator,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return int the number of products whose lines were merged or recounted */
    public function __invoke(ConsolidateGroceryListCommand $command): int
    {
        $list = $this->em->find(GroceryList::class, $command->groceryListId);

        return null === $list ? 0 : count($this->consolidator->apply($list));
    }
}
