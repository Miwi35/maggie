<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/** A meal's day has gone by while shopping for it is still on the list. */
final readonly class MealPassed
{
    public function __construct(
        public string $mealId,
    ) {
    }
}
