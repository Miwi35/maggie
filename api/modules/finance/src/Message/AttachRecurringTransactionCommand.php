<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class AttachRecurringTransactionCommand
{
    public function __construct(
        public string $transactionId,
    ) {
    }
}
