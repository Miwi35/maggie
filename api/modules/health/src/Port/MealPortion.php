<?php

declare(strict_types=1);

namespace Maggie\Health\Port;

/**
 * What one person eats of a planned meal (MAG-748): one serving of each of its
 * recipes. The figures are the recipe sheet's ({@see MealNutritionInterface});
 * `kcal` is null when no line of the recipes can be counted.
 */
final readonly class MealPortion
{
    public function __construct(
        public string $mealId,
        public string $date,
        public string $slot,
        public string $label,
        public ?int $kcal,
        public float $proteinG,
        public float $carbsG,
        public float $fatG,
    ) {
    }
}
