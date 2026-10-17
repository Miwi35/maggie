<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** The received transfer is a plain income again: its dette goes. */
final readonly class RemoveDebtCommand
{
    public function __construct(
        public string $userId,
        public string $transactionId,
    ) {
    }
}
