<?php

declare(strict_types=1);

namespace Maggie\Core\Reminder;

/** What one source did in one run: the notifications created, what to tell the console, what went wrong. */
final readonly class ReminderReport
{
    /**
     * @param list<string> $lines    one per batch of reminders sent
     * @param list<string> $warnings one per failure that was contained
     */
    public function __construct(
        public int $created = 0,
        public array $lines = [],
        public array $warnings = [],
    ) {
    }
}
