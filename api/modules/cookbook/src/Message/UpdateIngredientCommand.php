<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateIngredientCommand
{
    public function __construct(
        public string $ingredientId,
        public ?string $name = null,
        public ?string $category = null,
        public ?string $defaultUnit = null,
        public ?string $ciqualAlimCode = null,
        public ?float $kcalPer100g = null,
        public ?float $proteinPer100g = null,
        public ?float $carbsPer100g = null,
        public ?float $fatPer100g = null,
    ) {
    }
}
