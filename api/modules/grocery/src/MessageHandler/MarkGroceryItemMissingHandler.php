<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Message\MarkGroceryItemMissingCommand;
use Maggie\Grocery\UseCase\MarkGroceryItemMissing;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Uid\Ulid;

/** Returns the list so the projection publishes and reindexes it. */
#[AsMessageHandler]
class MarkGroceryItemMissingHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MarkGroceryItemMissing $markGroceryItemMissing,
    ) {
    }

    public function __invoke(MarkGroceryItemMissingCommand $command): GroceryList
    {
        $item = Ulid::isValid($command->groceryItemId)
            ? $this->em->find(GroceryItem::class, $command->groceryItemId)
            : null;

        if (null === $item || (string) $item->getGroceryList()->getUser()->getId() !== $command->userId) {
            throw new \DomainException("Grocery item not found: {$command->groceryItemId}");
        }

        return $this->markGroceryItemMissing->execute($item, $command->missing);
    }
}
