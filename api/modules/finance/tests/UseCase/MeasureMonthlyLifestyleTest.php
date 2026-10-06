<?php

namespace Maggie\Finance\Tests\UseCase;

use Maggie\Finance\UseCase\MeasureMonthlyLifestyle;
use PHPUnit\Framework\TestCase;

/**
 * The window the train de vie — and so the independence counter — is measured
 * over.
 *
 * Only the boundaries are tested here, against the real clock: the sum itself
 * belongs to the repository, and what used to go wrong was the window, not the
 * arithmetic. `bookedAt` is a date at midnight, so a window that kept the
 * current time of day dropped the 1st of the oldest month and took in the 1st
 * of the month in progress — the figure moved during the day, and no fixture
 * could catch it because the tests all pass an explicit month.
 */
class MeasureMonthlyLifestyleTest extends TestCase
{
    public function testTheDefaultWindowOpensAndClosesAtMidnight(): void
    {
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow();

        self::assertSame('00:00:00', $from->format('H:i:s'), 'the oldest month must start at midnight');
        self::assertSame('00:00:00', $until->format('H:i:s'), 'the current month must start at midnight');
    }

    public function testTheDefaultWindowIsTheWholeSampleBeforeTheCurrentMonth(): void
    {
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow();

        $firstOfThisMonth = new \DateTimeImmutable('midnight first day of this month');

        self::assertSame($firstOfThisMonth->format('Y-m-d H:i:s'), $until->format('Y-m-d H:i:s'));
        self::assertSame(
            $firstOfThisMonth->modify(sprintf('-%d months', MeasureMonthlyLifestyle::SAMPLE_MONTHS))->format('Y-m-d H:i:s'),
            $from->format('Y-m-d H:i:s'),
        );
    }

    public function testAGivenMonthIsTheUpperBoundAndTheSampleRunsBackFromIt(): void
    {
        [$from, $until] = MeasureMonthlyLifestyle::sampleWindow(new \DateTimeImmutable('2026-10-01 00:00:00'));

        self::assertSame('2026-07-01 00:00:00', $from->format('Y-m-d H:i:s'));
        self::assertSame('2026-10-01 00:00:00', $until->format('Y-m-d H:i:s'));
    }
}
