<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DeleteLoanCommand
{
    public function __construct(
        public string $loanId,
    ) {
    }
}
