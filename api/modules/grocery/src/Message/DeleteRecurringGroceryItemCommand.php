<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class DeleteRecurringGroceryItemCommand
{
    public function __construct(
        public string $recurringGroceryItemId,
    ) {
    }
}
