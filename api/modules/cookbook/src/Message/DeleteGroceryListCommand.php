<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class DeleteGroceryListCommand
{
    public function __construct(
        public string $groceryListId,
    ) {
    }
}
