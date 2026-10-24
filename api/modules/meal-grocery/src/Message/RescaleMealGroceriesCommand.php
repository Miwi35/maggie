<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Message;

/**
 * Scales what a meal holds on the grocery list to its guests now (MAG-482).
 *
 * `previousGuests` is the number the meal was cooked for until now; null when
 * it followed the default, which has not changed since.
 */
final readonly class RescaleMealGroceriesCommand
{
    public function __construct(
        public string $mealId,
        public ?int $previousGuests,
    ) {
    }
}
