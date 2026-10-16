<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class ReorderAislesCommand
{
    /** @param list<string> $aisleIds every aisle of the store, in the order of the way through it */
    public function __construct(
        public string $userId,
        public string $storeId,
        public array $aisleIds,
    ) {
    }
}
