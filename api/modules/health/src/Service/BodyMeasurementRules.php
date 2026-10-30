<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Exception\InvalidBodyMeasurementException;

/**
 * What a body measurement is held to whichever door it comes through (API,
 * MCP). Every refusal is an InvalidBodyMeasurementException naming the field:
 * a 400 for the API, an error for the tool. Nothing is deduced.
 */
final class BodyMeasurementRules
{
    public function source(string $value): MeasurementSource
    {
        return MeasurementSource::tryFrom($value)
            ?? throw new InvalidBodyMeasurementException('source', "unknown value \"{$value}\". Use manual, health_connect or withings.");
    }

    /** ISO 8601 with a time and an offset, e.g. 2026-10-21T07:12:00+02:00: a date alone or a time without offset is refused, never completed. */
    public function measuredAt(string $value, string $field = 'measuredAt'): \DateTimeImmutable
    {
        $hint = 'must be ISO 8601 with time and offset, e.g. 2026-10-21T07:12:00+02:00.';
        if (1 !== preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/', $value)) {
            throw new InvalidBodyMeasurementException($field, $hint);
        }

        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw new InvalidBodyMeasurementException($field, $hint);
        }
    }

    /** The figures are checked as they will be stored, rounded to their column's scale. */
    public function assert(?float $weightKg, ?float $bodyFatPercent, ?float $waistCm, ?string $externalId = null): void
    {
        if (null === $weightKg && null === $bodyFatPercent && null === $waistCm) {
            throw new InvalidBodyMeasurementException('weightKg', 'give at least one of weightKg, bodyFatPercent or waistCm.');
        }
        if (null !== $weightKg && (round($weightKg, 2) <= 0 || round($weightKg, 2) > BodyMeasurement::MAX_WEIGHT_KG)) {
            throw new InvalidBodyMeasurementException('weightKg', 'is in kilograms, more than 0 and at most 500: « 72,4 kg » is 72.4.');
        }
        if (null !== $bodyFatPercent && (round($bodyFatPercent, 1) <= 0 || round($bodyFatPercent, 1) >= BodyMeasurement::MAX_BODY_FAT_PERCENT)) {
            throw new InvalidBodyMeasurementException('bodyFatPercent', 'is in percent, more than 0 and less than 100.');
        }
        if (null !== $waistCm && (round($waistCm, 1) <= 0 || round($waistCm, 1) > BodyMeasurement::MAX_WAIST_CM)) {
            throw new InvalidBodyMeasurementException('waistCm', 'is in centimetres, more than 0 and at most 300.');
        }
        if (null !== $externalId && mb_strlen($externalId) > 255) {
            throw new InvalidBodyMeasurementException('externalId', 'must be at most 255 characters.');
        }
    }
}
