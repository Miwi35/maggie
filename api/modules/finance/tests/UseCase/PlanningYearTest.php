<?php

namespace Maggie\Finance\Tests\UseCase;

use Maggie\Finance\UseCase\PlanningYear;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Which year a planning session is about.
 *
 * Tested against fixed instants rather than against the clock: the rule only
 * changes behaviour in November and December, so a test that recomputed it
 * from `now` would exercise one branch for ten months of the year and go red
 * in production in exactly the month the session exists for.
 */
class PlanningYearTest extends TestCase
{
    /** @return iterable<string, array{string, int}> */
    public static function instants(): iterable
    {
        yield 'January plans the year it is in' => ['2026-01-01T09:00:00+01:00', 2026];
        yield 'the last day of October still plans this year' => ['2026-10-31T23:59:59+01:00', 2026];
        yield 'the first day of November plans the next one' => ['2026-11-01T00:00:00+01:00', 2027];
        yield 'December plans the next one' => ['2026-12-31T23:00:00+01:00', 2027];
        yield 'and the year after rolls over with it' => ['2027-11-15T12:00:00+01:00', 2028];
    }

    #[DataProvider('instants')]
    public function testTheDefaultYearIsTheOneBeingPrepared(string $now, int $expected): void
    {
        self::assertSame($expected, PlanningYear::default(new \DateTimeImmutable($now)));
    }

    /** @return iterable<string, array{int}> */
    public static function refusedYears(): iterable
    {
        yield 'before the range' => [1999];
        yield 'after the range' => [2101];
        // The one a model invents by typing a digit too many — and the one
        // that used to reach the database through the MCP tool.
        yield 'a digit too many' => [20330];
        // sprintf('%04d-01-01', -1) is not a date PHP will build.
        yield 'no year at all' => [0];
    }

    #[DataProvider('refusedYears')]
    public function testAYearNoSessionCouldBeAboutIsRefused(int $year): void
    {
        $this->expectException(\InvalidArgumentException::class);
        $this->expectExceptionMessage('year must be between 2000 and 2100.');

        PlanningYear::assert($year);
    }

    /** @return iterable<string, array{int}> */
    public static function acceptedYears(): iterable
    {
        yield 'the first' => [PlanningYear::MIN];
        yield 'the last' => [PlanningYear::MAX];
        yield 'one in the middle' => [2027];
    }

    #[DataProvider('acceptedYears')]
    public function testAYearInTheRangeIsAccepted(int $year): void
    {
        PlanningYear::assert($year);

        self::assertTrue(true, 'assert() returns nothing and must not throw');
    }
}
