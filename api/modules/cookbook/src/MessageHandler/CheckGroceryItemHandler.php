<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\GroceryItem;
use Maggie\Cookbook\Message\CheckGroceryItemCommand;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CheckGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(CheckGroceryItemCommand $command): GroceryItem
    {
        $item = $this->em->find(GroceryItem::class, $command->groceryItemId)
            ?? throw new \DomainException("Grocery item not found: {$command->groceryItemId}");

        $item->setChecked($command->checked);
        $this->em->flush();

        return $item;
    }
}
