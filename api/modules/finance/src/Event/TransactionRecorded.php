<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/** A transaction was stored, by whichever door: the REST API, a tool, an import or a bank sync. */
final readonly class TransactionRecorded
{
    public function __construct(
        public string $transactionId,
    ) {
    }
}
