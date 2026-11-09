<?php

declare(strict_types=1);

namespace Maggie\Grocery\Event;

/**
 * An errand ended in a store: its bought lines are archived (MAG-765). Finance may listen to it to match the bank
 * transaction. Plain values only: the errand is read-only, so they never go stale.
 */
final readonly class ErrandEnded
{
    public function __construct(
        public string $errandId,
        public string $userId,
        public ?string $storeId,
        public \DateTimeImmutable $endedAt,
        public ?int $paidAmountCents,
    ) {
    }
}
