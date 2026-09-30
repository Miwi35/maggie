<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateLoanCommand
{
    use ClearsFieldsTrait;

    /** @param list<'lender'> $clearFields */
    public function __construct(
        public string $loanId,
        public ?string $name = null,
        public ?int $principalRemainingCents = null,
        public ?int $monthlyPaymentCents = null,
        public ?int $annualRateBasisPoints = null,
        public ?string $lender = null,
        public ?int $priority = null,
        public ?string $currency = null,
        public array $clearFields = [],
    ) {
    }
}
