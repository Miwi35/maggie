<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class AddGroceryItemCommand
{
    public function __construct(
        public string $userId,
        public string $label,
        public ?float $quantity = null,
        public ?string $unit = null,
        public ?string $storeId = null,
        public ?string $storeName = null,
        public ?string $category = null,
        public string $source = 'manual',
    ) {
    }
}
