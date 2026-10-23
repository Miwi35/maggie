<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/**
 * The gap between two dates N, in plain words and facts only (MAG-550, spec
 * rule 11): never a verdict, never "better".
 */
final class NDifference
{
    /**
     * @param array{name: string, nDate: string|null} $scenario
     * @param array{name: string, nDate: string|null} $reference
     *
     * @return array{months: int|null, sentence: string}
     */
    public static function between(array $scenario, array $reference): array
    {
        $own = $scenario['nDate'];
        $other = $reference['nDate'];
        $against = '« '.$reference['name'].' »';

        if (null === $own && null === $other) {
            return ['months' => null, 'sentence' => "N non atteinte avant 20 ans, comme pour {$against}"];
        }
        if (null === $own) {
            return ['months' => null, 'sentence' => "N non atteinte avant 20 ans, alors qu'elle l'est en {$other} pour {$against}"];
        }
        if (null === $other) {
            return ['months' => null, 'sentence' => "N atteinte en {$own}, non atteinte avant 20 ans pour {$against}"];
        }

        $months = self::monthNumber($own) - self::monthNumber($other);
        if (0 === $months) {
            return ['months' => 0, 'sentence' => "N à la même date que {$against}"];
        }

        return [
            'months' => $months,
            'sentence' => \sprintf('N %s %s que %s', self::duration(abs($months)), $months < 0 ? 'plus tôt' : 'plus tard', $against),
        ];
    }

    /** 9 → « 9 mois », 12 → « 1 an », 27 → « 2 ans 3 mois ». */
    public static function duration(int $months): string
    {
        $years = intdiv($months, 12);
        $rest = $months % 12;

        $parts = [];
        if ($years > 0) {
            $parts[] = $years.(1 === $years ? ' an' : ' ans');
        }
        if ($rest > 0 || [] === $parts) {
            $parts[] = $rest.' mois';
        }

        return implode(' ', $parts);
    }

    private static function monthNumber(string $month): int
    {
        [$year, $number] = array_map('intval', explode('-', $month));

        return $year * 12 + $number;
    }
}
