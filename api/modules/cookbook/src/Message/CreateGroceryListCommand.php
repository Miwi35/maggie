<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateGroceryListCommand
{
    public function __construct(
        public string $userId,
        public string $weekStart,
        public string $status = 'draft',
    ) {
    }
}
