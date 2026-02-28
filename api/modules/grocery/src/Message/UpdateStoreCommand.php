<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class UpdateStoreCommand
{
    public function __construct(
        public string $storeId,
        public ?string $name = null,
        public ?string $description = null,
        public ?int $visitOrder = null,
    ) {
    }
}
