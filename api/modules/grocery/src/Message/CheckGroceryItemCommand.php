<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercureActionPayload;

final readonly class CheckGroceryItemCommand implements MercureActionPayload
{
    public function __construct(
        public string $groceryItemId,
        public string $userId,
        public bool $checked,
    ) {
    }

    public function toMercureActionPayload(): array
    {
        return ['action' => 'check', 'itemId' => $this->groceryItemId, 'checked' => $this->checked];
    }
}
