<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Event;

/**
 * A stored meal's own number of guests changed (MAG-482). `previousGuests` is
 * the number it named before, null when it followed the default. Not raised
 * when a meal is planned.
 */
final readonly class MealGuestsChanged
{
    public function __construct(
        public string $mealId,
        public ?int $previousGuests,
    ) {
    }
}
