<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercureActionPayload;

final readonly class ReorderGroceryItemsCommand implements MercureActionPayload
{
    /**
     * @param array<array{id: string, position: int}> $items
     */
    public function __construct(
        public string $userId,
        public array $items,
    ) {
    }

    public function toMercureActionPayload(): array
    {
        return ['action' => 'reorder', 'items' => $this->items];
    }
}
