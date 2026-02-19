<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class DeleteProductCommand
{
    public function __construct(
        public string $productId,
    ) {
    }
}
