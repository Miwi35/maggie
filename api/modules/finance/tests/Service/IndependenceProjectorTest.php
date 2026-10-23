<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Service\IndependenceProjector;
use PHPUnit\Framework\TestCase;

/**
 * The date N, the coverage curve and the dated milestones (MAG-549) on plain
 * figures. The current month is 2026-10, M0; rentes of 340 € against a train
 * de vie of 1 860 €, 4 % a year on what is invested.
 */
class IndependenceProjectorTest extends TestCase
{
    private const START = '2026-10-21 12:00:00';

    /** @param list<int> $investable */
    private function project(int $passive = 34000, int $lifestyle = 186000, array $investable = [], int $tail = 200000): array
    {
        return (new IndependenceProjector())->project(new \DateTimeImmutable(self::START), $passive, $lifestyle, $investable, $tail);
    }

    public function testItDatesNAndEachMilestoneFromWhatIsInvestedEveryMonth(): void
    {
        $projection = $this->project();

        // 2 000 € a month at 4 % add 6,67 € of rente each month invested.
        self::assertSame('2045-10', $projection['nDate']);
        self::assertSame(
            ['25' => '2028-05', '50' => '2034-03', '75' => '2040-01', '100' => '2045-10'],
            $projection['milestones'],
        );
    }

    public function testTheCurveStartsOnTheMeasuredCoverageAndStopsAtN(): void
    {
        $curve = $this->project()['curve'];

        self::assertSame(['month' => '2026-10', 'coverage' => 0.1827], $curve[0]);
        self::assertSame(['month' => '2045-10', 'coverage' => 1.0], $curve[array_key_last($curve)]);
        self::assertCount(229, $curve);
    }

    public function testTheCurveNeverGoesDown(): void
    {
        $coverage = array_column($this->project()['curve'], 'coverage');
        $sorted = $coverage;
        sort($sorted);

        self::assertSame($sorted, $coverage);
    }

    public function testTheMonthsOfThePlanningComeBeforeTheSteadyRhythm(): void
    {
        // Nothing invested the first 6 months (the projects take it all), then 2 000 €.
        $late = $this->project(investable: array_fill(0, 6, 0));

        self::assertSame('2046-04', $late['nDate']);
    }

    public function testWithoutCapacityNIsNotReachedWithinTwentyYearsAndThereIsNoError(): void
    {
        $projection = $this->project(passive: 0, tail: 0);

        self::assertNull($projection['nDate']);
        self::assertSame(['25' => null, '50' => null, '75' => null, '100' => null], $projection['milestones']);
        self::assertCount(IndependenceProjector::HORIZON_MONTHS, $projection['curve']);
        self::assertSame(0.0, $projection['curve'][IndependenceProjector::HORIZON_MONTHS - 1]['coverage']);
    }

    public function testACapacityTooSmallToReachNInTwentyYearsLeavesItUndated(): void
    {
        $projection = $this->project(tail: 50000);

        self::assertNull($projection['nDate']);
        self::assertNotNull($projection['milestones']['25']);
        self::assertNull($projection['milestones']['100']);
    }

    public function testRentesThatAlreadyCoverTheLifestyleMeanNIsNow(): void
    {
        $projection = $this->project(passive: 200000, tail: 0);

        self::assertSame('2026-10', $projection['nDate']);
        self::assertSame([['month' => '2026-10', 'coverage' => 1.0]], $projection['curve']);
        self::assertSame(['25' => '2026-10', '50' => '2026-10', '75' => '2026-10', '100' => '2026-10'], $projection['milestones']);
    }

    public function testWithoutAMeasuredLifestyleThereIsNothingToProject(): void
    {
        $projection = $this->project(passive: 34000, lifestyle: 0);

        self::assertNull($projection['nDate']);
        self::assertSame([], $projection['curve']);
        self::assertSame(['25' => null, '50' => null, '75' => null, '100' => null], $projection['milestones']);
    }

    public function testNImpactIsTheShiftOfNInMonths(): void
    {
        $projector = new IndependenceProjector();
        $base = $this->project();
        $later = $this->project(investable: array_fill(0, 6, 0));
        $sooner = $this->project(tail: 250000);

        self::assertSame(6, $projector->nImpactMonths($base, $later));
        self::assertLessThan(0, $projector->nImpactMonths($base, $sooner));
        self::assertSame(0, $projector->nImpactMonths($base, $base));
    }

    public function testNImpactIsUnknownWhenOneOfTheTwoIsNotReached(): void
    {
        $projector = new IndependenceProjector();

        self::assertNull($projector->nImpactMonths($this->project(), $this->project(tail: 0)));
        self::assertNull($projector->nImpactMonths($this->project(tail: 0), $this->project()));
    }
}
