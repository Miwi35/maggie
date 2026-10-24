<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Message;

final readonly class ReleaseMealGroceriesCommand
{
    public function __construct(
        public string $mealId,
    ) {
    }
}
