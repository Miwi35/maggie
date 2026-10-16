<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Recomputes the contact of every transaction of a user, after their counterparties changed. */
final readonly class RelinkContactTransactionsCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
