<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class DeleteStoreCommand
{
    public function __construct(
        public string $storeId,
    ) {
    }
}
