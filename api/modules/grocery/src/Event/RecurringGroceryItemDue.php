<?php

declare(strict_types=1);

namespace Maggie\Grocery\Event;

/** A recurring grocery item has come due: its period has run out since it was last added. */
final readonly class RecurringGroceryItemDue
{
    public function __construct(
        public string $itemId,
    ) {
    }
}
