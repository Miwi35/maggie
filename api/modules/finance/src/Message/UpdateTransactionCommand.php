<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateTransactionCommand
{
    use ClearsFieldsTrait;

    /** @param list<'categoryId'> $clearFields */
    public function __construct(
        public string $transactionId,
        public ?string $accountId = null,
        public ?int $amountCents = null,
        public ?string $label = null,
        public ?string $bookedAt = null,
        public ?string $status = null,
        public ?string $currency = null,
        public ?bool $isExceptional = null,
        public ?string $categoryId = null,
        public ?string $categorySource = null,
        public ?string $retrospect = null,
        public array $clearFields = [],
    ) {
    }
}
