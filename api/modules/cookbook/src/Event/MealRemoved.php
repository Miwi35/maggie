<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/**
 * A meal is gone from the agenda, whichever door removed it.
 *
 * The database cascade erases its contributions with it, so the event carries
 * what they were: the only record left of how much of each line was the meal's.
 */
final readonly class MealRemoved
{
    /** @param list<array{groceryItemId: string, quantity: float}> $contributions what the meal had put on the list */
    public function __construct(
        public string $mealId,
        public string $userId,
        public array $contributions = [],
    ) {
    }
}
