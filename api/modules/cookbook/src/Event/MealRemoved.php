<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/** A meal is gone from the agenda, whichever door removed it. */
final readonly class MealRemoved
{
    public function __construct(
        public string $mealId,
        public string $userId,
    ) {
    }
}
