<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class AddRecurringGroceryItemCommand
{
    public function __construct(
        public string $itemId,
    ) {
    }
}
