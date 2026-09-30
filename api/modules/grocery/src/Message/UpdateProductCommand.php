<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateProductCommand
{
    use ClearsFieldsTrait;

    /** @param list<'defaultUnit'> $clearFields */
    public function __construct(
        public string $productId,
        public ?string $name = null,
        public ?string $category = null,
        public ?string $defaultUnit = null,
        public array $clearFields = [],
    ) {
    }
}
