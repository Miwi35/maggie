<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateGroceryListCommand
{
    public function __construct(
        public string $groceryListId,
        public ?string $status = null,
    ) {
    }
}
