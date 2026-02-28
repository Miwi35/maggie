<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class CreateRecurringGroceryItemCommand
{
    public function __construct(
        public string $userId,
        public string $frequency,
        public ?string $productId = null,
        public ?string $customLabel = null,
        public ?float $quantity = null,
        public ?string $unit = null,
    ) {
    }
}
