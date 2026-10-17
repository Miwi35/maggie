<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** The transfer is no longer told apart: its allocations and what it created go. */
final readonly class RemoveAllocationCommand
{
    public function __construct(
        public string $userId,
        public string $transactionId,
    ) {
    }
}
