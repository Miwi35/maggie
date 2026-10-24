<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Message;

final readonly class SyncMealGroceriesCommand
{
    public function __construct(
        public string $mealId,
    ) {
    }
}
