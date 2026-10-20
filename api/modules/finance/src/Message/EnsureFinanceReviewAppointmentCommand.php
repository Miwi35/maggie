<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** Lays down the Finance agenda and the monthly review's appointment, once (MAG-456). */
final readonly class EnsureFinanceReviewAppointmentCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
