<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Grocery\Entity\GroceryList;

/** Takes a removed meal's share back off the list. */
class RevokeMealGroceries
{
    public function __construct(
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @param list<array{groceryItemId: string, quantity: float}> $contributions
     *
     * @return GroceryList|null the list that lost shopping, null when the meal had put nothing on it
     */
    public function execute(array $contributions): ?GroceryList
    {
        $list = $this->mealGrocerySync->revoke($contributions);

        if (null !== $list) {
            $this->em->flush();
        }

        return $list;
    }
}
