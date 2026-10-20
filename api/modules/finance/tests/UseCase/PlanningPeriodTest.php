<?php

namespace Maggie\Finance\Tests\UseCase;

use Maggie\Finance\UseCase\PlanningPeriod;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The stretch of months an augmented review looks back on and plans (MAG-462). */
class PlanningPeriodTest extends TestCase
{
    /** @return iterable<string, array{string, ?string}> */
    public static function reviewedMonths(): iterable
    {
        yield 'January closes nothing' => ['2027-01-01', null];
        yield 'February closes nothing' => ['2027-02-01', null];
        yield 'March closes the first quarter' => ['2027-03-01', '2027-Q1'];
        yield 'April closes nothing' => ['2027-04-01', null];
        yield 'May closes nothing' => ['2027-05-01', null];
        yield 'June closes the first semester, the longer of the two' => ['2027-06-01', '2027-S1'];
        yield 'July closes nothing' => ['2027-07-01', null];
        yield 'August closes nothing' => ['2027-08-01', null];
        yield 'September closes the third quarter' => ['2027-09-01', '2027-Q3'];
        yield 'October closes nothing' => ['2027-10-01', null];
        yield 'November closes nothing' => ['2027-11-01', null];
        yield 'December closes the year, the longest of the three' => ['2027-12-01', '2027'];
    }

    #[DataProvider('reviewedMonths')]
    public function testTheReviewOfAMonthClosesTheLongestPeriodEndingThere(string $month, ?string $expected): void
    {
        $period = PlanningPeriod::closingAt(new \DateTimeImmutable($month));

        self::assertSame($expected, $period?->key());
    }

    public function testAQuarterSpansThreeMonthsAndTheNextOneFollows(): void
    {
        $period = PlanningPeriod::parse('2027-Q1');

        self::assertSame(PlanningPeriod::QUARTER, $period->level);
        self::assertSame(3, $period->months());
        self::assertSame('2027-01-01', $period->start()->format('Y-m-d'));
        self::assertSame('2027-04-01', $period->end()->format('Y-m-d'));
        self::assertSame(['2027-01', '2027-02', '2027-03'], array_map(
            static fn (\DateTimeImmutable $month): string => $month->format('Y-m'),
            $period->monthStarts(),
        ));
        self::assertSame('2027-Q2', $period->next()->key());
        self::assertSame('2026-Q4', $period->previous()->key());
    }

    public function testASemesterRollsOverTheYear(): void
    {
        $period = PlanningPeriod::parse('2027-S2');

        self::assertSame('2027-07-01', $period->start()->format('Y-m-d'));
        self::assertSame('2028-01-01', $period->end()->format('Y-m-d'));
        self::assertSame('2028-S1', $period->next()->key());
        self::assertSame('2027-S1', $period->previous()->key());
        self::assertSame('2026-S2', PlanningPeriod::parse('2027-S1')->previous()->key());
    }

    public function testAYearIsItsOwnKey(): void
    {
        $period = PlanningPeriod::parse('2027');

        self::assertSame(PlanningPeriod::YEAR, $period->level);
        self::assertSame(12, $period->months());
        self::assertSame('2028', $period->next()->key());
        self::assertSame('2026', $period->previous()->key());
        self::assertSame(
            ['key' => '2027', 'level' => 'year', 'from' => '2027-01', 'to' => '2027-12', 'months' => 12],
            $period->describe(),
        );
    }

    public function testItKnowsWhichDaysItContains(): void
    {
        $period = PlanningPeriod::parse('2027-Q2');

        self::assertTrue($period->contains(new \DateTimeImmutable('2027-04-01')));
        self::assertTrue($period->contains(new \DateTimeImmutable('2027-06-30 23:59:59')));
        self::assertFalse($period->contains(new \DateTimeImmutable('2027-07-01')));
        self::assertFalse($period->contains(new \DateTimeImmutable('2027-03-31')));
    }

    /** @return iterable<string, array{string}> */
    public static function refusedKeys(): iterable
    {
        yield 'empty' => [''];
        yield 'a fifth quarter' => ['2027-Q5'];
        yield 'a third semester' => ['2027-S3'];
        yield 'a month' => ['2027-03'];
        yield 'lower case' => ['2027-q1'];
        yield 'a year out of range' => ['1999-Q1'];
        yield 'a digit too many' => ['20270'];
    }

    #[DataProvider('refusedKeys')]
    public function testAKeyNoSessionCouldBeAboutIsRefused(string $key): void
    {
        $this->expectException(\InvalidArgumentException::class);

        PlanningPeriod::parse($key);
    }

    public function testThePeriodAfterTheLastYearIsRefused(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        PlanningPeriod::parse('2100-Q4')->next();
    }
}
