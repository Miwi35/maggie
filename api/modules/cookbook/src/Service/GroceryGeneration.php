<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Grocery\Entity\GroceryList;

/** What generating the list gave: the list, and the meals whose ingredients still wait for a choice. */
final readonly class GroceryGeneration
{
    /** @param list<Meal> $mealsToChoose */
    public function __construct(
        public GroceryList $list,
        public array $mealsToChoose,
    ) {
    }
}
