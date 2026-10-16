<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Lays down the user's empty review for this date, on first read (MAG-500). */
final readonly class CreateDayReviewCommand
{
    public function __construct(
        public string $userId,
        public \DateTimeImmutable $date,
    ) {
    }
}
