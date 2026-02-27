<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\GroceryItem;
use Maggie\Cookbook\Message\RemoveGroceryItemCommand;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RemoveGroceryItemHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(RemoveGroceryItemCommand $command): void
    {
        $item = $this->em->find(GroceryItem::class, $command->groceryItemId)
            ?? throw new \DomainException("Grocery item not found: {$command->groceryItemId}");

        $list = $item->getGroceryList();
        $list->removeItem($item);
        $this->em->remove($item);

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();
    }
}
