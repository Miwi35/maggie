<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class CreateBodyMeasurementCommand
{
    /** @param string $measuredAt ISO 8601 with time and offset */
    public function __construct(
        public string $userId,
        public string $measuredAt,
        public ?float $weightKg = null,
        public ?float $bodyFatPercent = null,
        public ?float $waistCm = null,
        public string $source = 'manual',
        public ?string $externalId = null,
        public bool $excluded = false,
    ) {
    }
}
