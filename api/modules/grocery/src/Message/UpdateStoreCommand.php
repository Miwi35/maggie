<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateStoreCommand
{
    use ClearsFieldsTrait;

    /** @param list<'description'> $clearFields */
    public function __construct(
        public string $storeId,
        public ?string $name = null,
        public ?string $description = null,
        public ?int $visitOrder = null,
        public array $clearFields = [],
    ) {
    }
}
