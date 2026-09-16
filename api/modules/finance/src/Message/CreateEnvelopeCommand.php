<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateEnvelopeCommand
{
    public function __construct(
        public string $userId,
        public string $categoryId,
        public int $amountCents,
        public int $year,
        public string $mode = 'monthly',
        public ?int $month = null,
        public string $currency = 'EUR',
    ) {
    }
}
