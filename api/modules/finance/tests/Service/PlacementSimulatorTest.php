<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Enum\PlacementFrequency;
use Maggie\Finance\Service\PlacementSimulator;
use PHPUnit\Framework\TestCase;

/** Compound interest reinvested monthly, deposits at the end of their period (MAG-555). */
class PlacementSimulatorTest extends TestCase
{
    private PlacementSimulator $simulator;

    protected function setUp(): void
    {
        $this->simulator = new PlacementSimulator();
    }

    public function testAPeaOf2000PlusAMonthly150At6PercentOver20Years(): void
    {
        $result = $this->simulator->simulate(200000, 15000, PlacementFrequency::Monthly, 600, 20);

        // 2 000 × 1,06^20 + 150 × ((1,06^20 − 1) / r), r = 1,06^(1/12) − 1: 74 430,07 €.
        $r = 1.06 ** (1 / 12) - 1;
        $expected = 200000 * 1.06 ** 20 + 15000 * (1.06 ** 20 - 1) / $r;
        self::assertEqualsWithDelta($expected, $result['final'], 100);
        self::assertEqualsWithDelta(7443007, $result['final'], 100);
        self::assertSame(200000 + 240 * 15000, $result['paid']);
        self::assertSame($result['final'] - $result['paid'], $result['interest']);
        // 4 % a year, a month of it.
        self::assertSame((int) round($result['final'] * 0.04 / 12), $result['rent']);
    }

    public function testTheCurveRunsMonthByMonthFromDayOne(): void
    {
        $result = $this->simulator->simulate(200000, 15000, PlacementFrequency::Monthly, 600, 20);

        self::assertCount(241, $result['curve']);
        self::assertSame(['month' => 0, 'capital' => 200000, 'paid' => 200000], $result['curve'][0]);
        self::assertSame(240, $result['curve'][240]['month']);
        self::assertSame($result['final'], $result['curve'][240]['capital']);
        self::assertSame($result['paid'], $result['curve'][240]['paid']);
        // The first deposit lands at the end of month 1, after its interest.
        self::assertSame(215000, $result['curve'][1]['paid']);
        self::assertSame((int) round(200000 * 1.06 ** (1 / 12) + 15000), $result['curve'][1]['capital']);
    }

    public function testASingleDepositAloneCompoundsFromDayOne(): void
    {
        $result = $this->simulator->simulate(0, 50000, PlacementFrequency::Once, 300, 10);

        // 500 € × 1,03^10 = 671,96 €.
        self::assertEqualsWithDelta(67196, $result['final'], 100);
        self::assertSame(50000, $result['paid']);
        self::assertSame(50000, $result['curve'][0]['paid']);
        self::assertSame(50000, $result['curve'][120]['paid']);
    }

    public function testAYearlyDepositLandsAtTheEndOfEachYear(): void
    {
        $result = $this->simulator->simulate(0, 120000, PlacementFrequency::Yearly, 350, 15);

        // 1 200 € a year at 3,5 %, end of year: Σ 1 200 × 1,035^(15 − k), 23 154,82 €.
        self::assertEqualsWithDelta(2315482, $result['final'], 100);
        self::assertSame(15 * 120000, $result['paid']);
        self::assertSame(0, $result['curve'][11]['paid']);
        self::assertSame(120000, $result['curve'][12]['paid']);
    }

    public function testAQuarterlyDepositLandsEveryThreeMonths(): void
    {
        $result = $this->simulator->simulate(0, 30000, PlacementFrequency::Quarterly, 400, 10);

        // 300 € a quarter at 4 %: Σ 300 × 1,04^((120 − 3k) / 12), 14 621,67 €.
        self::assertEqualsWithDelta(1462167, $result['final'], 100);
        self::assertSame(40 * 30000, $result['paid']);
        self::assertSame(0, $result['curve'][2]['paid']);
        self::assertSame(30000, $result['curve'][3]['paid']);
    }

    public function testNoReturnKeepsWhatWasPaid(): void
    {
        $result = $this->simulator->simulate(100000, 10000, PlacementFrequency::Monthly, 0, 1);

        self::assertSame(220000, $result['final']);
        self::assertSame(0, $result['interest']);
    }

    public function testAnOutgrownCapitalIsHeldAtTheLargestInt(): void
    {
        $result = $this->simulator->simulate(1000000000, 0, PlacementFrequency::Once, 10000, 60);

        self::assertSame(\PHP_INT_MAX, $result['final']);
    }
}
