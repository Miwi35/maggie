<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/** A stored transaction changed what its category and its pairing are read from. */
final readonly class TransactionChanged
{
    /** @param list<string> $changedFields among label, amountCents, bookedAt and account */
    public function __construct(
        public string $transactionId,
        public array $changedFields,
    ) {
    }
}
