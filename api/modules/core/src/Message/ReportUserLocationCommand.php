<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

/** The phone says where the owner is (MAG-650): it replaces the last position. */
final readonly class ReportUserLocationCommand
{
    public function __construct(
        public string $userId,
        public float $latitude,
        public float $longitude,
        public float $accuracy,
        public \DateTimeImmutable $capturedAt,
    ) {
    }
}
