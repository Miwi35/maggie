<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/** Changes the default guests of the user's meals (MAG-482). */
final readonly class UpdateMealSettingsCommand
{
    /** @param mixed $defaultGuests checked by the handler: a whole number of at least 1 */
    public function __construct(
        public string $userId,
        public string $mealSettingsId,
        public mixed $defaultGuests,
    ) {
    }
}
