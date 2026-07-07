<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateTransactionCommand
{
    public function __construct(
        public string $userId,
        public string $accountId,
        public int $amountCents,
        public string $label,
        public string $bookedAt,
        public string $status = 'spent',
        public string $currency = 'EUR',
        public bool $isExceptional = false,
        public ?string $categoryId = null,
    ) {
    }
}
