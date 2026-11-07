<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Null leaves a field untouched; `clearQuantity` empties the grams. The
 * figures are taken as given: nothing is recomputed from Ciqual or the meal.
 */
final readonly class UpdateFoodIntakeCommand
{
    public function __construct(
        public string $userId,
        public string $foodIntakeId,
        public ?string $eatenAt = null,
        public ?string $label = null,
        public ?float $quantityGrams = null,
        public ?int $kcal = null,
        public ?float $proteinG = null,
        public ?float $carbsG = null,
        public ?float $fatG = null,
        public bool $clearQuantity = false,
    ) {
    }
}
