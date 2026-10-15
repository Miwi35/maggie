<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateTransactionCommand
{
    /** @param array<string, mixed>|null $qualification the owner's own, whole (`Qualification::toArray()`) */
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
        public ?string $note = null,
        public ?array $qualification = null,
    ) {
    }
}
