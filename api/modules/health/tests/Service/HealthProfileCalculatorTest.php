<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Enum\ActivityLevel;
use Maggie\Health\Enum\NeedMode;
use Maggie\Health\Enum\Sex;
use Maggie\Health\Service\HealthProfileCalculator;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The day's need (MAG-707): Mifflin-St Jeor, the activity factor, the need typed. */
class HealthProfileCalculatorTest extends TestCase
{
    public function testAManOf38178Cm72KgHasABasalMetabolismOf1648(): void
    {
        // 720 + 1112.5 − 190 + 5 = 1647.5, rounded up.
        self::assertSame(1648, HealthProfileCalculator::bmrKcal(Sex::Male, 38, 178, 72.0));
    }

    public function testAWomanTakes161Off(): void
    {
        // 600 + 1031.25 − 150 − 161 = 1320.25.
        self::assertSame(1320, HealthProfileCalculator::bmrKcal(Sex::Female, 30, 165, 60.0));
    }

    /** @return iterable<string, array{ActivityLevel, int}> */
    public static function factors(): iterable
    {
        // 1648 × factor, rounded to ten kcal.
        yield 'sedentary × 1.2' => [ActivityLevel::Sedentary, 1980];
        yield 'light × 1.375' => [ActivityLevel::Light, 2270];
        yield 'moderate × 1.55' => [ActivityLevel::Moderate, 2550];
        yield 'active × 1.725' => [ActivityLevel::Active, 2840];
        yield 'very active × 1.9' => [ActivityLevel::VeryActive, 3130];
    }

    #[DataProvider('factors')]
    public function testTheComputedNeedIsTheBasalMetabolismTimesTheFactorRoundedToTen(ActivityLevel $level, int $need): void
    {
        self::assertSame($need, HealthProfileCalculator::needKcal(NeedMode::Computed, 1648, $level, 2500));
        self::assertSame($level->factor(), HealthProfileCalculator::factor(NeedMode::Computed, 1648, $need, $level));
    }

    public function testTheEnteredNeedIsTheValueTypedAndItsFactorTheRatio(): void
    {
        self::assertSame(2200, HealthProfileCalculator::needKcal(NeedMode::Entered, 1648, ActivityLevel::Sedentary, 2200));
        self::assertSame(1.33, HealthProfileCalculator::factor(NeedMode::Entered, 1648, 2200, ActivityLevel::Sedentary));
        // Entered without a basal metabolism: the need stands, the ratio cannot be told.
        self::assertSame(2200, HealthProfileCalculator::needKcal(NeedMode::Entered, null, ActivityLevel::Sedentary, 2200));
        self::assertNull(HealthProfileCalculator::factor(NeedMode::Entered, null, 2200, ActivityLevel::Sedentary));
    }

    /** @return iterable<string, array{?Sex, ?int, ?int, ?float}> */
    public static function incompleteProfiles(): iterable
    {
        yield 'no sex' => [null, 38, 178, 72.0];
        yield 'no age' => [Sex::Male, null, 178, 72.0];
        yield 'no height' => [Sex::Male, 38, null, 72.0];
        yield 'no weight' => [Sex::Male, 38, 178, null];
    }

    #[DataProvider('incompleteProfiles')]
    public function testAnIncompleteProfileHasNeitherBasalMetabolismNorComputedNeed(?Sex $sex, ?int $age, ?int $heightCm, ?float $weightKg): void
    {
        $bmr = HealthProfileCalculator::bmrKcal($sex, $age, $heightCm, $weightKg);

        self::assertNull($bmr);
        self::assertNull(HealthProfileCalculator::needKcal(NeedMode::Computed, $bmr, ActivityLevel::Sedentary, null));
    }

    public function testTheAgeIsTheCurrentYearLessTheYearOfBirth(): void
    {
        self::assertSame(38, HealthProfileCalculator::age(1988, 2026));
        self::assertNull(HealthProfileCalculator::age(null, 2026));
    }
}
