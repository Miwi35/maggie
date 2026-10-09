<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Service\GroceryListItems;
use Maggie\Grocery\Specification\IsRecurringGroceryItemDue;

/**
 * Puts a recurring item on its owner's list when it is due, and restarts its clock.
 *
 * The single place where a recurring item reaches the list, whoever asks: the
 * daily command through the event, or the `generate_grocery_list` tool. Due is
 * checked again here, so an item that another path just added is not added twice.
 */
class AddRecurringGroceryItem
{
    public function __construct(
        private readonly GroceryListItems $groceryListItems,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return GroceryList|null the list that gained a line, null when the item is not due or its need was already waiting */
    public function execute(RecurringGroceryItem $item): ?GroceryList
    {
        $due = IsRecurringGroceryItemDue::today();
        if (!$due->isSatisfiedBy($item)) {
            return null;
        }

        $user = $item->getUser();
        $product = $item->getProduct();

        // Set even when the line is already there: the need is covered, and the
        // clock restarts from the day it was. The flush below carries it.
        $item->setLastAddedAt($due->day());

        if (null !== $product) {
            if ($this->groceryListItems->hasOpenLineOf($user, $product)) {
                $this->em->flush();

                return null;
            }

            return $this->groceryListItems->addProduct($user, $product, $item->getQuantity(), $item->getUnit(), GroceryItemSource::Recurring);
        }

        $label = $item->getCustomLabel() ?? $item->getLabel();
        if ($this->groceryListItems->hasOpenLineLabelled($user, $label)) {
            $this->em->flush();

            return null;
        }

        return $this->groceryListItems->addLabelled($user, $label, $item->getQuantity(), $item->getUnit(), GroceryItemSource::Recurring);
    }
}
