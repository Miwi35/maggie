<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Null leaves a field untouched; the `clear*` flags empty an optional one
 * (a merge-patch `{"waistCm": null}`). « Écarter » is this command with
 * `excluded: true`, and `excluded: false` takes the measurement back.
 */
final readonly class UpdateBodyMeasurementCommand
{
    public function __construct(
        public string $userId,
        public string $measurementId,
        public ?string $measuredAt = null,
        public ?float $weightKg = null,
        public ?float $bodyFatPercent = null,
        public ?float $waistCm = null,
        public ?string $source = null,
        public ?string $externalId = null,
        public ?bool $excluded = null,
        public bool $clearWeight = false,
        public bool $clearBodyFat = false,
        public bool $clearWaist = false,
        public bool $clearExternalId = false,
    ) {
    }
}
