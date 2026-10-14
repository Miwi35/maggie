<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/**
 * A bank sync stored its movements, already filed under their categories.
 * Whatever reacts to the money a sync brings in listens here, not inside the
 * sync — the first is the envelope alert (MAG-49).
 */
final readonly class BankSyncCompleted
{
    /**
     * @param list<string> $transactionIds the movements this sync stored
     */
    public function __construct(
        public string $userId,
        public array $transactionIds,
    ) {
    }
}
