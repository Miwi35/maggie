<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * With `mealId`, « Mangé »: the meal's figures are copied and the others are
 * ignored. Without it, `eatenAt` is required, and the figures are given or
 * computed from `ciqualCode` and `quantityGrams`.
 */
final readonly class CreateFoodIntakeCommand
{
    /** @param string|null $eatenAt ISO 8601 with time and offset */
    public function __construct(
        public string $userId,
        public ?string $mealId = null,
        public ?string $eatenAt = null,
        public ?string $label = null,
        public ?string $ciqualCode = null,
        public ?float $quantityGrams = null,
        public ?int $kcal = null,
        public ?float $proteinG = null,
        public ?float $carbsG = null,
        public ?float $fatG = null,
        public string $source = 'manual',
    ) {
    }
}
