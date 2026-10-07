<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Enum\Unit;

/**
 * How many packagings to buy. `converted` is false when the quantity could
 * not be computed (a jar of unknown content, units of another family): the
 * count is then 1, and the caller says the quantity is not computed.
 */
final readonly class PackagedQuantity
{
    public function __construct(
        public int $count,
        public Unit $unit,
        public bool $converted,
    ) {
    }
}
