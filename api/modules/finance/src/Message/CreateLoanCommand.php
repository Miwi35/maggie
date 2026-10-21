<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class CreateLoanCommand
{
    /**
     * @param array<string, mixed> $details the type and conditions (MAG-522), see LoanDetails
     */
    public function __construct(
        public string $userId,
        public string $name,
        public int $principalRemainingCents,
        public int $monthlyPaymentCents,
        public int $annualRateBasisPoints = 0,
        public ?string $lender = null,
        public int $priority = 0,
        public string $currency = 'EUR',
        public array $details = [],
    ) {
    }
}
