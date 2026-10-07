<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateProductCommand
{
    use ClearsFieldsTrait;

    /** @param list<'defaultUnit'|'preferredStore'|'fallbackStore'|'shelfLifeDays'|'packagingUnit'|'packagingSize'|'packagingSizeUnit'> $clearFields */
    public function __construct(
        public string $productId,
        public ?string $name = null,
        public ?string $category = null,
        public ?string $defaultUnit = null,
        public ?string $preferredStoreId = null,
        public ?string $fallbackStoreId = null,
        public ?int $shelfLifeDays = null,
        public ?string $packagingUnit = null,
        public ?float $packagingSize = null,
        public ?string $packagingSizeUnit = null,
        public array $clearFields = [],
    ) {
    }
}
