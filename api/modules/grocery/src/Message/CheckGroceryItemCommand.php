<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class CheckGroceryItemCommand
{
    public function __construct(
        public string $groceryItemId,
        public bool $checked,
    ) {
    }
}
