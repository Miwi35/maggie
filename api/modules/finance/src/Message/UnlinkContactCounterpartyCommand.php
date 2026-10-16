<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Removes a counterparty: the transfers it recognised are detached. */
final readonly class UnlinkContactCounterpartyCommand
{
    public function __construct(
        public string $userId,
        public string $counterpartyId,
    ) {
    }
}
