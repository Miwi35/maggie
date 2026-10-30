<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Enum\ActivityLevel;
use Maggie\Health\Enum\NeedMode;
use Maggie\Health\Enum\Sex;
use Maggie\Health\Enum\StepSource;
use Maggie\Health\Exception\InvalidHealthProfileException;

/**
 * What a health profile is held to whichever door it comes through (API,
 * MCP), MAG-707. Every refusal is an InvalidHealthProfileException naming the
 * field: a 400 for the API, an error for the tool. Nothing is rounded or
 * completed.
 */
final class HealthProfileRules
{
    public function sex(mixed $value): Sex
    {
        return (\is_string($value) ? Sex::tryFrom($value) : null)
            ?? throw new InvalidHealthProfileException('sex', 'use male or female.');
    }

    public function birthYear(mixed $value, int $currentYear): int
    {
        if (!\is_int($value) || $value > $currentYear || $value < $currentYear - 120) {
            throw new InvalidHealthProfileException('birthYear', \sprintf('is a year from %d to %d: 38 years old in %d is %d.', $currentYear - 120, $currentYear, $currentYear, $currentYear - 38));
        }

        return $value;
    }

    public function heightCm(mixed $value): int
    {
        if (!\is_int($value) || $value < HealthProfile::MIN_HEIGHT_CM || $value > HealthProfile::MAX_HEIGHT_CM) {
            throw new InvalidHealthProfileException('heightCm', \sprintf('is a whole number of centimetres from %d to %d.', HealthProfile::MIN_HEIGHT_CM, HealthProfile::MAX_HEIGHT_CM));
        }

        return $value;
    }

    /** Checked here, before anything is written, with the weighing's own bounds. */
    public function weightKg(mixed $value): float
    {
        if ((!\is_int($value) && !\is_float($value)) || round((float) $value, 2) <= 0 || round((float) $value, 2) > BodyMeasurement::MAX_WEIGHT_KG) {
            throw new InvalidHealthProfileException('weightKg', 'is in kilograms, more than 0 and at most 500: « 72,4 kg » is 72.4.');
        }

        return (float) $value;
    }

    public function activityLevel(mixed $value): ActivityLevel
    {
        return (\is_string($value) ? ActivityLevel::tryFrom($value) : null)
            ?? throw new InvalidHealthProfileException('activityLevel', 'use sedentary, light, moderate, active or very_active.');
    }

    public function mode(mixed $value): NeedMode
    {
        return (\is_string($value) ? NeedMode::tryFrom($value) : null)
            ?? throw new InvalidHealthProfileException('mode', 'use computed or entered.');
    }

    public function enteredNeedKcal(mixed $value): int
    {
        if (!\is_int($value)
            || $value < HealthProfile::MIN_ENTERED_NEED_KCAL
            || $value > HealthProfile::MAX_ENTERED_NEED_KCAL
            || 0 !== $value % HealthProfile::ENTERED_NEED_STEP_KCAL) {
            throw new InvalidHealthProfileException('enteredNeedKcal', \sprintf('is a whole number of kcal from %d to %d, by steps of %d.', HealthProfile::MIN_ENTERED_NEED_KCAL, HealthProfile::MAX_ENTERED_NEED_KCAL, HealthProfile::ENTERED_NEED_STEP_KCAL));
        }

        return $value;
    }

    public function stepGoal(mixed $value): int
    {
        if (!\is_int($value) || $value < HealthProfile::STEP_GOAL_STEP || $value > HealthProfile::MAX_STEP_GOAL || 0 !== $value % HealthProfile::STEP_GOAL_STEP) {
            throw new InvalidHealthProfileException('stepGoal', \sprintf('is a number of steps from %d to %d, by steps of %d.', HealthProfile::STEP_GOAL_STEP, HealthProfile::MAX_STEP_GOAL, HealthProfile::STEP_GOAL_STEP));
        }

        return $value;
    }

    public function time(mixed $value, string $field): string
    {
        if (!\is_string($value) || 1 !== preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $value)) {
            throw new InvalidHealthProfileException($field, 'is a time « HH:MM », e.g. 08:00.');
        }

        return $value;
    }

    /** « HH:MM » strings compare as the times they spell. */
    public function window(string $start, string $end): void
    {
        if ($start >= $end) {
            throw new InvalidHealthProfileException('suggestionWindowEnd', "must come after suggestionWindowStart ({$start}).");
        }
    }

    public function sessionMinutes(mixed $value): int
    {
        if (!\is_int($value) || !\in_array($value, HealthProfile::SESSION_MINUTES, true)) {
            throw new InvalidHealthProfileException('sessionMinutes', 'use 15, 20 or 30.');
        }

        return $value;
    }

    public function stepSource(mixed $value): StepSource
    {
        return (\is_string($value) ? StepSource::tryFrom($value) : null)
            ?? throw new InvalidHealthProfileException('stepSource', 'use health_connect or none.');
    }
}
