<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercurePatchable;

final readonly class ReorderGroceryItemsCommand implements MercurePatchable
{
    /**
     * @param array<array{id: string, position: int}> $items
     */
    public function __construct(
        public string $userId,
        public array $items,
    ) {
    }

    public function toMercurePatch(): array
    {
        return ['action' => 'reorder', 'items' => $this->items];
    }
}
