<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class GenerateGroceryListCommand
{
    public function __construct(
        public string $userId,
        public string $fromDate,
        public string $toDate,
    ) {
    }
}
