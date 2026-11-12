<?php

namespace Maggie\Calendar\Tests\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Service\PeriodRecurrence;
use PHPUnit\Framework\TestCase;

/** The occurrences of a period (MAG-809): on its wall clock when timed, on its days when all-day. */
class PeriodRecurrenceTest extends TestCase
{
    private static function period(string $rrule): Period
    {
        return (new Period())->setName('Garde')->setAgenda(new Agenda())->setRrule($rrule);
    }

    public function testAFortnightlyTimedPeriodKeepsItsWallClockAcrossDst(): void
    {
        $period = self::period('FREQ=WEEKLY;INTERVAL=2')->scheduleTimed(
            new \DateTimeImmutable('2037-03-06T18:00:00+01:00'),
            new \DateTimeImmutable('2037-03-13T18:00:00+01:00'),
        );

        $occurrences = (new PeriodRecurrence())->expandAll(
            [$period],
            new \DateTimeImmutable('2037-03-01T00:00:00+01:00'),
            new \DateTimeImmutable('2037-04-12T00:00:00+02:00'),
        );

        $paris = new \DateTimeZone('Europe/Paris');
        self::assertSame(
            ['2037-03-06T18:00:00+01:00', '2037-03-20T18:00:00+01:00', '2037-04-03T18:00:00+02:00'],
            array_map(static fn (Period $p): string => (string) $p->getStartAt()?->setTimezone($paris)->format('c'), $occurrences),
        );
        // A week long each, the clocks' change included: 18:00 to 18:00.
        self::assertSame('2037-04-10T18:00:00+02:00', $occurrences[2]->getEndAt()?->setTimezone($paris)->format('c'));
        self::assertSame([(string) $period->getId()], array_values(array_unique(array_map(static fn (Period $p): string => (string) $p->getId(), $occurrences))));
    }

    public function testAnAllDayYearlyPeriodExpandsOnDays(): void
    {
        $period = self::period('FREQ=YEARLY')->scheduleAllDay(new \DateTimeImmutable('2030-12-25'), new \DateTimeImmutable('2030-12-27'));

        $occurrences = (new PeriodRecurrence())->expandAll(
            [$period],
            new \DateTimeImmutable('2037-12-01T00:00:00+01:00'),
            new \DateTimeImmutable('2038-01-01T00:00:00+01:00'),
        );

        self::assertCount(1, $occurrences);
        self::assertSame('2037-12-25', $occurrences[0]->getStartDate()?->format('Y-m-d'));
        self::assertSame('2037-12-27', $occurrences[0]->getEndDate()?->format('Y-m-d'));
        self::assertNull($occurrences[0]->getStartAt());
    }

    public function testASinglePeriodOutsideTheRangeIsLeftOut(): void
    {
        $inside = (new Period())->setName('Dedans')->scheduleAllDay(new \DateTimeImmutable('2037-03-01'), new \DateTimeImmutable('2037-03-02'));
        $outside = (new Period())->setName('Dehors')->scheduleAllDay(new \DateTimeImmutable('2037-03-02'), new \DateTimeImmutable('2037-03-03'));

        $periods = (new PeriodRecurrence())->expandAll(
            [$outside, $inside],
            new \DateTimeImmutable('2037-03-01T00:00:00+01:00'),
            new \DateTimeImmutable('2037-03-02T00:00:00+01:00'),
        );

        self::assertSame(['Dedans'], array_map(static fn (Period $p): string => $p->getName(), $periods));
    }
}
