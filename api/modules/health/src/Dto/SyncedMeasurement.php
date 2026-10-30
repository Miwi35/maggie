<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Maggie\Health\Entity\BodyMeasurement;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

/** A weighing or a body fat reading of a Health Connect batch (MAG-708), as the JSON gave it: see {@see HealthSyncRequest}. */
final class SyncedMeasurement
{
    /** ISO 8601 with a time and an offset: the rules of Workout and BodyMeasurement refuse anything else. */
    public const INSTANT = '/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/';

    public const SOURCES = ['health_connect'];

    public function __construct(
        #[Assert\Sequentially([new Assert\NotBlank(), new Assert\Type('string'), new Assert\Length(max: 255)])]
        public readonly mixed $externalId = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('string'), new Assert\Regex(pattern: self::INSTANT, message: 'Must be ISO 8601 with time and offset, e.g. 2026-10-21T07:12:00+02:00.')])]
        public readonly mixed $measuredAt = null,
        #[Assert\Sequentially([new Assert\Type('numeric'), new Assert\Positive(), new Assert\LessThanOrEqual(BodyMeasurement::MAX_WEIGHT_KG)])]
        public readonly mixed $weightKg = null,
        #[Assert\Sequentially([new Assert\Type('numeric'), new Assert\Positive(), new Assert\LessThan(BodyMeasurement::MAX_BODY_FAT_PERCENT)])]
        public readonly mixed $bodyFatPercent = null,
        #[Assert\Choice(choices: self::SOURCES)]
        public readonly mixed $source = 'health_connect',
    ) {
    }

    /** @param array<string, mixed> $item */
    public static function fromArray(array $item): self
    {
        return new self(
            externalId: $item['externalId'] ?? null,
            measuredAt: $item['measuredAt'] ?? null,
            weightKg: $item['weightKg'] ?? null,
            bodyFatPercent: $item['bodyFatPercent'] ?? null,
            source: $item['source'] ?? 'health_connect',
        );
    }

    #[Assert\Callback]
    public function validateFigures(ExecutionContextInterface $context): void
    {
        if (null === $this->weightKg && null === $this->bodyFatPercent) {
            $context->buildViolation('Give at least one of weightKg or bodyFatPercent.')->atPath('weightKg')->addViolation();
        }
        // Checked as stored, rounded to the column's scale, as the measurement's rules do.
        if (is_numeric($this->weightKg) && round((float) $this->weightKg, 2) <= 0) {
            $context->buildViolation('Is in kilograms, more than 0 once rounded to 2 decimals.')->atPath('weightKg')->addViolation();
        }
        if (is_numeric($this->bodyFatPercent) && (round((float) $this->bodyFatPercent, 1) <= 0 || round((float) $this->bodyFatPercent, 1) >= BodyMeasurement::MAX_BODY_FAT_PERCENT)) {
            $context->buildViolation('Is in percent, more than 0 and less than 100 once rounded to 1 decimal.')->atPath('bodyFatPercent')->addViolation();
        }
    }
}
