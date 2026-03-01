<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercurePatchable;

final readonly class RemoveGroceryItemCommand implements MercurePatchable
{
    public function __construct(
        public string $groceryItemId,
    ) {
    }

    public function toMercurePatch(): array
    {
        return ['action' => 'remove', 'itemId' => $this->groceryItemId];
    }
}
