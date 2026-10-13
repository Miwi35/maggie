<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Time;

use Maggie\Core\Time\Weekday;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The day of the week Maggie announces comes from the code, never from the model (MAG-431). */
final class WeekdayTest extends TestCase
{
    /** @return iterable<string, array{string, string}> */
    public static function days(): iterable
    {
        yield 'the 24th of October 2026 that Maggie called a Monday' => ['2026-10-24', 'samedi'];
        yield 'monday' => ['2026-10-26', 'lundi'];
        yield 'tuesday' => ['2026-10-27', 'mardi'];
        yield 'wednesday' => ['2026-10-28', 'mercredi'];
        yield 'thursday' => ['2026-10-29', 'jeudi'];
        yield 'friday' => ['2026-10-30', 'vendredi'];
        yield 'sunday' => ['2026-11-01', 'dimanche'];
    }

    #[DataProvider('days')]
    public function testItNamesTheDayInFrench(string $day, string $expected): void
    {
        self::assertSame($expected, Weekday::fr(new \DateTimeImmutable($day)));
    }

    public function testItReadsTheInstantInTheZoneItIsGivenIn(): void
    {
        // Saturday 22:30 UTC is already Sunday 00:30 in Paris (summer time).
        $instant = new \DateTimeImmutable('2026-10-24T22:30:00+00:00');

        self::assertSame('samedi', Weekday::fr($instant));
        self::assertSame('dimanche', Weekday::fr($instant->setTimezone(new \DateTimeZone('Europe/Paris'))));
    }
}
