<?php

declare(strict_types=1);

namespace Maggie\Memory\Message;

final readonly class DeleteMemoryCommand
{
    public function __construct(
        public string $memoryId,
    ) {
    }
}
