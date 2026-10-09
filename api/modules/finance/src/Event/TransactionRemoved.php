<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/** A transaction that was paired with another one is gone; the other one is left pointing at nothing. */
final readonly class TransactionRemoved
{
    public function __construct(
        public string $transactionId,
        public string $counterpartId,
    ) {
    }
}
