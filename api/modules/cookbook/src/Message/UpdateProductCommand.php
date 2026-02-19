<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateProductCommand
{
    public function __construct(
        public string $productId,
        public ?string $name = null,
        public ?string $category = null,
        public ?string $defaultUnit = null,
    ) {
    }
}
