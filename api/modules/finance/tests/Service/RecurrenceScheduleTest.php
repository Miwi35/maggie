<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Service\RecurrenceSchedule;
use PHPUnit\Framework\TestCase;

/**
 * When a series falls due, counted from its anchor and never stored.
 */
class RecurrenceScheduleTest extends TestCase
{
    private function series(string $anchorOn, RecurrencePeriod $period = RecurrencePeriod::Monthly, DayRule $dayRule = DayRule::FixedDay, ?string $endsOn = null): RecurringOperation
    {
        return (new RecurringOperation())
            ->setLabel('Abonnement')
            ->setReferenceAmountCents(-1349)
            ->setPeriod($period)
            ->setDayRule($dayRule)
            ->setAnchorOn(new \DateTimeImmutable($anchorOn))
            ->setEndsOn(null === $endsOn ? null : new \DateTimeImmutable($endsOn));
    }

    /**
     * @param list<\DateTimeImmutable> $dates
     *
     * @return list<string>
     */
    private static function days(array $dates): array
    {
        return array_map(static fn (\DateTimeImmutable $d) => $d->format('Y-m-d'), $dates);
    }

    private function between(RecurringOperation $series, string $from, string $to): array
    {
        return self::days((new RecurrenceSchedule())->occurrencesBetween($series, new \DateTimeImmutable($from), new \DateTimeImmutable($to)));
    }

    public function testAnAnchorOnThe31stClampsFebruaryAndComesBackInMarch(): void
    {
        self::assertSame(
            ['2027-01-31', '2027-02-28', '2027-03-31', '2027-04-30', '2027-05-31'],
            $this->between($this->series('2027-01-31'), '2027-01-01', '2027-06-01'),
        );
    }

    public function testTheLastDayOfFebruaryIsThe29thInALeapYear(): void
    {
        $series = $this->series('2027-01-10', dayRule: DayRule::LastDayOfMonth);

        self::assertSame(['2028-02-29'], $this->between($series, '2028-02-01', '2028-03-01'));
        self::assertSame(['2027-02-28'], $this->between($series, '2027-02-01', '2027-03-01'));
    }

    public function testAQuarterlySeriesAnchoredInJanuaryFallsInAprilJulyAndOctober(): void
    {
        self::assertSame(
            ['2027-01-15', '2027-04-15', '2027-07-15', '2027-10-15'],
            $this->between($this->series('2027-01-15', RecurrencePeriod::Quarterly), '2027-01-01', '2028-01-01'),
        );
    }

    public function testAWeeklySeriesStepsSevenDays(): void
    {
        self::assertSame(
            ['2027-03-02', '2027-03-09', '2027-03-16', '2027-03-23', '2027-03-30', '2027-04-06'],
            $this->between($this->series('2027-03-02', RecurrencePeriod::Weekly), '2027-03-01', '2027-04-07'),
        );
    }

    public function testAYearlySeriesOnThe29thOfFebruaryClampsOnOrdinaryYears(): void
    {
        self::assertSame(
            ['2028-02-29', '2029-02-28', '2030-02-28', '2031-02-28', '2032-02-29'],
            $this->between($this->series('2028-02-29', RecurrencePeriod::Yearly), '2028-01-01', '2033-01-01'),
        );
    }

    public function testNothingFallsAfterTheEnd(): void
    {
        $series = $this->series('2027-01-12', endsOn: '2027-03-12');

        self::assertSame(['2027-01-12', '2027-02-12', '2027-03-12'], $this->between($series, '2027-01-01', '2028-01-01'));
        self::assertNull((new RecurrenceSchedule())->nextOccurrenceAfter($series, new \DateTimeImmutable('2027-03-12')));
    }

    public function testNothingFallsBeforeTheAnchor(): void
    {
        self::assertSame([], $this->between($this->series('2027-05-12'), '2027-01-01', '2027-05-12'));
    }

    public function testTheWindowIncludesItsFirstDayAndExcludesItsLast(): void
    {
        $series = $this->series('2027-01-12');

        self::assertSame(['2027-02-12'], $this->between($series, '2027-02-12', '2027-03-12'));
        self::assertSame(['2027-02-12'], $this->between($series, '2027-02-12 15:30', '2027-03-12 08:00'));
    }

    public function testTheNextOccurrenceIsStrictlyAfterTheDay(): void
    {
        $schedule = new RecurrenceSchedule();
        $series = $this->series('2027-01-12');

        self::assertSame('2027-02-12', $schedule->nextOccurrenceAfter($series, new \DateTimeImmutable('2027-01-12'))?->format('Y-m-d'));
        self::assertSame('2027-02-12', $schedule->nextOccurrenceAfter($series, new \DateTimeImmutable('2027-02-11'))?->format('Y-m-d'));
        self::assertSame('2027-01-12', $schedule->nextOccurrenceAfter($series, new \DateTimeImmutable('2026-06-01'))?->format('Y-m-d'));
        self::assertSame('2027-03-31', $schedule->nextOccurrenceAfter($this->series('2027-01-31'), new \DateTimeImmutable('2027-02-28'))?->format('Y-m-d'));
    }

    public function testADayBelongsToTheNearestOccurrence(): void
    {
        $schedule = new RecurrenceSchedule();
        $series = $this->series('2027-01-01');

        // A salary nine days late still belongs to its own month.
        self::assertSame('2027-03-01', $schedule->occurrenceFor($series, new \DateTimeImmutable('2027-03-10'))?->format('Y-m-d'));
        // Paid two days early, at the end of the month before.
        self::assertSame('2027-04-01', $schedule->occurrenceFor($series, new \DateTimeImmutable('2027-03-30'))?->format('Y-m-d'));
        // Before the anchor, the first occurrence is the only candidate.
        self::assertSame('2027-01-01', $schedule->occurrenceFor($series, new \DateTimeImmutable('2026-12-29'))?->format('Y-m-d'));
    }

    public function testAnEndedSeriesHasNoOccurrenceAfterItsEnd(): void
    {
        $schedule = new RecurrenceSchedule();
        $series = $this->series('2027-01-01', endsOn: '2027-02-01');

        self::assertSame('2027-02-01', $schedule->occurrenceFor($series, new \DateTimeImmutable('2027-02-25'))?->format('Y-m-d'));
        self::assertNull($schedule->occurrenceFor($this->series('2027-01-01', endsOn: '2026-12-01'), new \DateTimeImmutable('2027-01-01')));
    }
}
