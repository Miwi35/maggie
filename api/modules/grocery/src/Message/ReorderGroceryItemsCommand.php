<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class ReorderGroceryItemsCommand
{
    /**
     * @param array<array{id: string, position: int}> $items
     */
    public function __construct(
        public string $userId,
        public array $items,
    ) {
    }
}
