<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class DeleteRecurringGroceryItemCommand
{
    public function __construct(
        public string $recurringGroceryItemId,
    ) {
    }
}
