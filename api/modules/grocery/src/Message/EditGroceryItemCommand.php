<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class EditGroceryItemCommand
{
    public function __construct(
        public string $groceryItemId,
        public string $userId,
        public ?string $label = null,
        public ?float $quantity = null,
        public ?string $unit = null,
        public ?string $storeId = null,
        public ?string $storeName = null,
        public ?string $category = null,
    ) {
    }
}
