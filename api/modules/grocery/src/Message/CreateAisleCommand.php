<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class CreateAisleCommand
{
    /** @param ?int $position 1 = first on the way through the store; null puts it last */
    public function __construct(
        public string $userId,
        public string $storeId,
        public string $name,
        public ?int $position = null,
    ) {
    }
}
