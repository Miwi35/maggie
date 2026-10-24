<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Port;

use Maggie\Cookbook\Entity\Meal;

/**
 * What a freshly planned meal could put on the grocery list, answered by the
 * module that knows the list (meal-grocery): cookbook only relays it.
 */
interface MealGroceryPreviewInterface
{
    /** @return array<string, mixed> */
    public function preview(Meal $meal): array;
}
