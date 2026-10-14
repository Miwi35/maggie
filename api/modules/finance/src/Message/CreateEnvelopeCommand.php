<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * A monthly or annual envelope budgets `categoryId` for `year` (and `month`);
 * a one-off one is only a `name` and an amount, with neither.
 */
final readonly class CreateEnvelopeCommand
{
    public function __construct(
        public string $userId,
        public ?string $categoryId,
        public int $amountCents,
        public ?int $year,
        public string $mode = 'monthly',
        public ?int $month = null,
        public string $currency = 'EUR',
        public ?string $name = null,
        public ?int $alertThresholdPercent = null,
    ) {
    }
}
