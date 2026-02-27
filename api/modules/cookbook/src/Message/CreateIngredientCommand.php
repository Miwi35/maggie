<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateIngredientCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public string $category,
        public ?string $defaultUnit = null,
        public ?string $ciqualFoodId = null,
        public ?float $kcalPer100g = null,
        public ?float $proteinPer100g = null,
        public ?float $carbsPer100g = null,
        public ?float $fatPer100g = null,
    ) {
    }
}
