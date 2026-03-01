<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercureActionPayload;

final readonly class RemoveGroceryItemCommand implements MercureActionPayload
{
    public function __construct(
        public string $groceryItemId,
    ) {
    }

    public function toMercureActionPayload(): array
    {
        return ['action' => 'remove', 'itemId' => $this->groceryItemId];
    }
}
