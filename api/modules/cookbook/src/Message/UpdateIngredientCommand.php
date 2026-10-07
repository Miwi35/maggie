<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateIngredientCommand
{
    use ClearsFieldsTrait;

    /** @param list<'defaultUnit'|'ciqualAlimCode'|'kcalPer100g'|'proteinPer100g'|'carbsPer100g'|'fatPer100g'|'packagingUnit'|'packagingSize'|'packagingSizeUnit'> $clearFields */
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
        public ?string $packagingUnit = null,
        public ?float $packagingSize = null,
        public ?string $packagingSizeUnit = null,
        public array $clearFields = [],
    ) {
    }
}
