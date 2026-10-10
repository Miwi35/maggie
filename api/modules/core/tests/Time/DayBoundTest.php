<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Time;

use Maggie\Core\Time\DayBound;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * The days of a range `[from, to)` of instants, read in Paris (MAG-382) — the
 * one function the API's filters, Elasticsearch and the repository share.
 */
final class DayBoundTest extends TestCase
{
    /** @return iterable<string, array{string, string}> */
    public static function starts(): iterable
    {
        yield 'midnight in Paris' => ['2037-01-01T00:00:00+01:00', '2037-01-01'];
        // Midnight of the 1st in Paris is still the 31st in UTC.
        yield 'the same midnight, sent in UTC' => ['2036-12-31T23:00:00Z', '2037-01-01'];
        yield 'a bare day' => ['2037-01-01', '2037-01-01'];
        yield 'during the day' => ['2037-01-01T15:30:00+01:00', '2037-01-01'];
        yield 'summer time' => ['2037-07-01T00:00:00+02:00', '2037-07-01'];
    }

    #[DataProvider('starts')]
    public function testTheFirstDayIsTheDayTheStartFallsOn(string $from, string $day): void
    {
        self::assertSame($day, DayBound::firstDay($from)->format('Y-m-d'));
    }

    /** @return iterable<string, array{string, string}> */
    public static function ends(): iterable
    {
        // The end is excluded: a range ending at midnight of the 2nd ends on the 1st.
        yield 'midnight in Paris' => ['2037-01-02T00:00:00+01:00', '2037-01-01'];
        yield 'the same midnight, sent in UTC' => ['2037-01-01T23:00:00Z', '2037-01-01'];
        yield 'a bare day' => ['2037-01-02', '2037-01-01'];
        yield 'the last second of a day' => ['2037-01-01T23:59:59+01:00', '2037-01-01'];
    }

    #[DataProvider('ends')]
    public function testTheLastDayIsTheDayOfTheInstantBeforeTheEnd(string $to, string $day): void
    {
        self::assertSame($day, DayBound::lastDay($to)->format('Y-m-d'));
    }

    public function testAnOperatorNamesWhichEndOfTheRangeItIs(): void
    {
        self::assertSame(['gte', '2037-01-01'], self::shown(DayBound::forOperator('after', '2037-01-01T00:00:00+01:00')));
        self::assertSame(['gte', '2037-01-01'], self::shown(DayBound::forOperator('strictly_after', '2037-01-01T00:00:00+01:00')));
        self::assertSame(['lte', '2037-01-01'], self::shown(DayBound::forOperator('before', '2037-01-02T00:00:00+01:00')));
        self::assertSame(['lte', '2037-01-01'], self::shown(DayBound::forOperator('strictly_before', '2037-01-02T00:00:00+01:00')));
        self::assertNull(DayBound::forOperator('between', '2037-01-01'));
    }

    public function testAnEmptyBoundIsNotNow(): void
    {
        $this->expectException(\InvalidArgumentException::class);

        DayBound::firstDay(' ');
    }

    /**
     * @param array{0: string, 1: \DateTimeImmutable}|null $bound
     *
     * @return array{string, string}|null
     */
    private static function shown(?array $bound): ?array
    {
        return null === $bound ? null : [$bound[0], $bound[1]->format('Y-m-d')];
    }
}
