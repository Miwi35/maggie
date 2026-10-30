<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Enum\ActivityLevel;
use Maggie\Health\Enum\NeedMode;
use Maggie\Health\Enum\Sex;

/**
 * The day's need (MAG-707), the single place its arithmetic lives: the basal
 * metabolism by Mifflin-St Jeor, multiplied by the activity factor and
 * rounded to ten kcal, or the need the user typed. A figure that is missing
 * gives null, never a guess.
 */
final class HealthProfileCalculator
{
    /** 10 × weight + 6.25 × height − 5 × age + 5 (man) or − 161 (woman), rounded. */
    public static function bmrKcal(?Sex $sex, ?int $age, ?int $heightCm, ?float $weightKg): ?int
    {
        if (null === $sex || null === $age || null === $heightCm || null === $weightKg) {
            return null;
        }

        return (int) round(10 * $weightKg + 6.25 * $heightCm - 5 * $age + $sex->bmrConstant());
    }

    /** Computed: round(bmr × factor / 10) × 10. Entered: the value typed. */
    public static function needKcal(NeedMode $mode, ?int $bmrKcal, ActivityLevel $activityLevel, ?int $enteredNeedKcal): ?int
    {
        if (NeedMode::Entered === $mode) {
            return $enteredNeedKcal;
        }

        return null === $bmrKcal ? null : (int) (round($bmrKcal * $activityLevel->factor() / 10) * 10);
    }

    /**
     * The factor shown in the ring: the activity's when computed, need ÷ basal
     * metabolism when entered, to two decimals; null when either is missing.
     */
    public static function factor(NeedMode $mode, ?int $bmrKcal, ?int $needKcal, ActivityLevel $activityLevel): ?float
    {
        if (NeedMode::Computed === $mode) {
            return $activityLevel->factor();
        }

        return null === $bmrKcal || null === $needKcal || $bmrKcal <= 0 ? null : round($needKcal / $bmrKcal, 2);
    }

    public static function age(?int $birthYear, int $currentYear): ?int
    {
        return null === $birthYear ? null : $currentYear - $birthYear;
    }
}
