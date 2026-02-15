<?php

declare(strict_types=1);

namespace Maggie\Memory\Message;

final readonly class StoreMemoryCommand
{
    public function __construct(
        public string $type,
        public string $content,
        public ?string $metadata = null,
        public ?string $userId = null,
    ) {
    }
}
