<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class AddGroceryItemCommand
{
    public function __construct(
        public string $groceryListId,
        public ?string $productId = null,
        public ?string $customLabel = null,
        public ?float $quantity = null,
        public ?string $unit = null,
    ) {
    }
}
