<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Lays down the user's empty day for this date, on first read (MAG-497). */
final readonly class CreateDayPlanCommand
{
    public function __construct(
        public string $userId,
        public \DateTimeImmutable $date,
    ) {
    }
}
