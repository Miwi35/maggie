<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class MoveToFallbackCommand
{
    public function __construct(
        public string $userId,
        public string $storeId,
    ) {
    }
}
