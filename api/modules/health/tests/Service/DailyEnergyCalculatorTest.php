<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Service\DailyEnergyCalculator;
use PHPUnit\Framework\TestCase;

/** The consolidated daily energy (MAG-746): the sessions Health Connect saw are inside activeKcal. */
class DailyEnergyCalculatorTest extends TestCase
{
    public function testASessionSeenByHealthConnectIsNotCountedTwice(): void
    {
        self::assertSame(
            ['steps' => 8000, 'activeKcal' => 400, 'bmrKcal' => 1648, 'workoutKcal' => 300, 'stepsKcal' => 100, 'expendedKcal' => 2048],
            DailyEnergyCalculator::day(8000, 400, 1648, [['kcal' => 300, 'seenByHealthConnect' => true]]),
        );
    }

    public function testASessionHealthConnectDidNotSeeIsAddedOnce(): void
    {
        $day = DailyEnergyCalculator::day(3000, 150, 1648, [['kcal' => 200, 'seenByHealthConnect' => false]]);

        self::assertSame(200, $day['workoutKcal']);
        self::assertSame(150, $day['stepsKcal']);
        self::assertSame(1648 + 150 + 200, $day['expendedKcal']);
    }

    public function testStepsKcalIsNeverNegative(): void
    {
        $day = DailyEnergyCalculator::day(1000, 100, 1648, [['kcal' => 300, 'seenByHealthConnect' => true]]);

        self::assertSame(0, $day['stepsKcal']);
        self::assertSame(1648 + 300, $day['expendedKcal']);
    }

    public function testWithoutAProfileTheBaseAndTheTotalAreNull(): void
    {
        $day = DailyEnergyCalculator::day(8000, 400, null, []);

        self::assertNull($day['bmrKcal']);
        self::assertNull($day['expendedKcal']);
        self::assertSame(400, $day['stepsKcal']);
    }

    public function testADayWithoutHealthConnectDataHasNoStepsAndStillCountsASession(): void
    {
        $day = DailyEnergyCalculator::day(null, null, 1648, [['kcal' => 200, 'seenByHealthConnect' => false]]);

        self::assertNull($day['steps']);
        self::assertNull($day['stepsKcal']);
        self::assertSame(1848, $day['expendedKcal']);
    }

    public function testASessionWithoutKcalIsEstimatedFromMetWeightAndDuration(): void
    {
        // 3.8 × 72 × 0.5 h = 136.8
        self::assertSame(137, DailyEnergyCalculator::estimateKcal(WorkoutType::Walk, 30, 72.0));
        self::assertNull(DailyEnergyCalculator::estimateKcal(WorkoutType::Walk, 30, null));
    }
}
