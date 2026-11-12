<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Holiday;

use Maggie\Calendar\Holiday\GenevaHolidays;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The public holidays of Geneva (MAG-810), computed: Easter by the anonymous Gregorian algorithm. */
final class GenevaHolidaysTest extends TestCase
{
    public function testEasterBasedDaysOf2027(): void
    {
        $days = GenevaHolidays::forYear(2027);

        // Easter 2027 is Sunday 28 March.
        self::assertSame('2027-03-28', GenevaHolidays::easter(2027)->format('Y-m-d'));
        self::assertSame('Vendredi saint', $days['2027-03-26']);
        self::assertSame('Lundi de Pâques', $days['2027-03-29']);
        self::assertSame('Ascension', $days['2027-05-06']);
        self::assertSame('Lundi de Pentecôte', $days['2027-05-17']);
        self::assertCount(9, $days);
        self::assertSame(array_keys($days), array_values(array_unique(array_keys($days))));
    }

    /** @return iterable<string, array{int, string}> */
    public static function easters(): iterable
    {
        yield '2024' => [2024, '2024-03-31'];
        yield '2025' => [2025, '2025-04-20'];
        yield '2026' => [2026, '2026-04-05'];
        yield '2038' => [2038, '2038-04-25'];
        yield '2285' => [2285, '2285-03-22'];
    }

    #[DataProvider('easters')]
    public function testEasterIsTheGregorianOne(int $year, string $easter): void
    {
        self::assertSame($easter, GenevaHolidays::easter($year)->format('Y-m-d'));
    }

    public function testJeuneGenevoisIsTheThursdayAfterTheFirstSundayOfSeptember(): void
    {
        // 2027: the 1st of September is a Wednesday, the first Sunday the 5th.
        self::assertSame('Jeûne genevois', GenevaHolidays::forYear(2027)['2027-09-09']);
        // 2030: the 1st of September is itself a Sunday.
        self::assertSame('Jeûne genevois', GenevaHolidays::forYear(2030)['2030-09-05']);
    }

    public function testRestaurationIsTheThirtyFirstOfDecember(): void
    {
        $days = GenevaHolidays::forYear(2027);

        self::assertSame('Restauration de la République', $days['2027-12-31']);
        self::assertSame('2027-12-31', array_key_last($days));
        self::assertSame('Nouvel An', $days['2027-01-01']);
        self::assertSame('Fête nationale', $days['2027-08-01']);
        self::assertSame('Noël', $days['2027-12-25']);
    }
}
