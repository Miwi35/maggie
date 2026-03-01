<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercurePatchable;

final readonly class CheckGroceryItemCommand implements MercurePatchable
{
    public function __construct(
        public string $groceryItemId,
        public bool $checked,
    ) {
    }

    public function toMercurePatch(): array
    {
        return ['action' => 'check', 'itemId' => $this->groceryItemId, 'checked' => $this->checked];
    }
}
