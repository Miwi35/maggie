<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

final readonly class CreateProductCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public string $category,
        public ?string $defaultUnit = null,
    ) {
    }
}
