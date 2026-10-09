<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class ReleaseCounterpartCommand
{
    public function __construct(
        public string $transactionId,
        public string $removedTransactionId,
    ) {
    }
}
