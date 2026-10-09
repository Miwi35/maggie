<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * The catch-up pass over a user's history, through the bus so every line it
 * attaches and every series it recalibrates is published and reindexed.
 */
final readonly class AttachRecurringOperationsCommand
{
    public function __construct(
        public string $userId,
        public ?int $limitDays = null,
        public bool $dryRun = false,
    ) {
    }
}
