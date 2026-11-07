<?php

declare(strict_types=1);

namespace Maggie\Health\Port;

/** A Ciqual food and what 100 g of it bring (MAG-748); a figure Ciqual does not give is null. */
final readonly class CiqualFood
{
    public function __construct(
        public string $code,
        public string $name,
        public ?float $kcalPer100g,
        public ?float $proteinPer100g,
        public ?float $carbsPer100g,
        public ?float $fatPer100g,
    ) {
    }
}
