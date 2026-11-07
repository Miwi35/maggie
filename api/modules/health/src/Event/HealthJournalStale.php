<?php

declare(strict_types=1);

namespace Maggie\Health\Event;

/**
 * The journal days of this user from `$from` on (all of them when null) may
 * have an outdated expenditure (MAG-750): a weighing moves the weight known on
 * the days after it, the profile changes every day's basal metabolism.
 */
final readonly class HealthJournalStale
{
    /** @param string|null $from the first local day concerned, YYYY-MM-DD */
    public function __construct(
        public string $userId,
        public ?string $from = null,
    ) {
    }
}
