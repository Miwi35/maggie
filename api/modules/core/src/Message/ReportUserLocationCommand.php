<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

use Maggie\Core\Enum\LocationSource;

/** The phone says where the owner is (MAG-650): it replaces the last position of the same source (MAG-666). */
final readonly class ReportUserLocationCommand
{
    public function __construct(
        public string $userId,
        public float $latitude,
        public float $longitude,
        public float $accuracy,
        public \DateTimeImmutable $capturedAt,
        public LocationSource $source,
    ) {
    }
}
