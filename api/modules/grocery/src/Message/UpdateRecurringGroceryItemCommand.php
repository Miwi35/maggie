<?php

declare(strict_types=1);

namespace Maggie\Grocery\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateRecurringGroceryItemCommand
{
    use ClearsFieldsTrait;

    /** @param list<'productId'|'customLabel'|'quantity'|'unit'> $clearFields */
    public function __construct(
        public string $recurringGroceryItemId,
        public ?string $frequency = null,
        public ?string $productId = null,
        public ?string $customLabel = null,
        public ?float $quantity = null,
        public ?string $unit = null,
        public array $clearFields = [],
    ) {
    }
}
