<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Contract\MercureActionPayload;

/** Marks a grocery article missing in its store (`missing: true`) or cancels it (`false`) (MAG-764). */
final readonly class MarkGroceryItemMissingCommand implements MercureActionPayload
{
    public function __construct(
        public string $groceryItemId,
        public string $userId,
        public bool $missing,
    ) {
    }

    public function toMercureActionPayload(): array
    {
        return ['action' => 'missing', 'itemId' => $this->groceryItemId, 'missing' => $this->missing];
    }
}
