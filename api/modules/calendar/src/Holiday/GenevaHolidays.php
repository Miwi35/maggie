<?php

declare(strict_types=1);

namespace Maggie\Calendar\Holiday;

/**
 * The public holidays of the canton of Geneva (MAG-810), where the owner works:
 * no API gives them, so the year is computed here. The movable days hang on
 * Easter, found with the anonymous Gregorian algorithm — no `ext-calendar`.
 */
final class GenevaHolidays
{
    /** @return array<string, string> the day, YYYY-MM-DD, to its name, by date */
    public static function forYear(int $year): array
    {
        $easter = self::easter($year);
        $firstSundayOfSeptember = (new \DateTimeImmutable(\sprintf('%d-09-01', $year)))->modify('first sunday of this month');

        $days = [
            \sprintf('%d-01-01', $year) => 'Nouvel An',
            $easter->modify('-2 days')->format('Y-m-d') => 'Vendredi saint',
            $easter->modify('+1 day')->format('Y-m-d') => 'Lundi de Pâques',
            $easter->modify('+39 days')->format('Y-m-d') => 'Ascension',
            $easter->modify('+50 days')->format('Y-m-d') => 'Lundi de Pentecôte',
            \sprintf('%d-08-01', $year) => 'Fête nationale',
            $firstSundayOfSeptember->modify('+4 days')->format('Y-m-d') => 'Jeûne genevois',
            \sprintf('%d-12-25', $year) => 'Noël',
            \sprintf('%d-12-31', $year) => 'Restauration de la République',
        ];
        ksort($days);

        return $days;
    }

    /** Easter Sunday of that year (Meeus/Jones/Butcher). */
    public static function easter(int $year): \DateTimeImmutable
    {
        $a = $year % 19;
        $b = intdiv($year, 100);
        $c = $year % 100;
        $d = intdiv($b, 4);
        $e = $b % 4;
        $f = intdiv($b + 8, 25);
        $g = intdiv($b - $f + 1, 3);
        $h = (19 * $a + $b - $d - $g + 15) % 30;
        $i = intdiv($c, 4);
        $k = $c % 4;
        $l = (32 + 2 * $e + 2 * $i - $h - $k) % 7;
        $m = intdiv($a + 11 * $h + 22 * $l, 451);
        $month = intdiv($h + $l - 7 * $m + 114, 31);
        $day = (($h + $l - 7 * $m + 114) % 31) + 1;

        return new \DateTimeImmutable(\sprintf('%d-%02d-%02d', $year, $month, $day));
    }
}
