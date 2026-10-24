<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\MealGrocery\Service\MealGrocerySync;

/** Takes a removed meal's share back off the list. */
class RevokeMealGroceries
{
    public function __construct(
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @param list<array{groceryItemId: string, quantity: float, unit: ?string}> $contributions
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
