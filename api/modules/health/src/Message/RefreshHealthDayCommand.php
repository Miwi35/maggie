<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/** Read a health journal day's computed columns again from the detailed tables (MAG-750). */
final readonly class RefreshHealthDayCommand
{
    /** @param string $date the local day, YYYY-MM-DD */
    public function __construct(
        public string $userId,
        public string $date,
    ) {
    }
}
