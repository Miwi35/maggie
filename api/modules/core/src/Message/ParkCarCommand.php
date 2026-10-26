<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

/** The phone says where the car was left (MAG-650): it replaces the last place, the position stays. */
final readonly class ParkCarCommand
{
    public function __construct(
        public string $userId,
        public float $latitude,
        public float $longitude,
        public \DateTimeImmutable $parkedAt,
    ) {
    }
}
