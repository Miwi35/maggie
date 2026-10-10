<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/** A stored meal moved to another day. Not raised when a meal is planned. */
final readonly class MealRescheduled
{
    public function __construct(
        public string $mealId,
    ) {
    }
}
