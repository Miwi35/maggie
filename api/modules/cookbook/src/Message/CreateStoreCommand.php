<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateStoreCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public ?string $description = null,
        public int $visitOrder = 0,
    ) {
    }
}
