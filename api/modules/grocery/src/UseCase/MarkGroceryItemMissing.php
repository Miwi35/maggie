<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Psr\Clock\ClockInterface;

/**
 * An article missing in the store: off to the product's fallback store, if
 * any, until it is found or bought there; cancelling puts it back (MAG-764).
 */
class MarkGroceryItemMissing
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function execute(GroceryItem $item, bool $missing): GroceryList
    {
        $now = $this->clock->now();

        if ($missing) {
            $item->markMissing($now);
        } else {
            $item->clearMissing();
        }

        $list = $item->getGroceryList();
        $list->setUpdatedAt($now);
        $this->em->flush();

        return $list;
    }
}
