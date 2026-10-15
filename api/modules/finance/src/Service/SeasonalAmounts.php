<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/**
 * The seasons of a monthly envelope: an amount for a stretch of the year
 * (Cinéma 45 € from November to February, 20 € the rest of the time).
 *
 * A season runs from `fromMonth` to `toMonth` included and may cross the new
 * year (11 → 2). Two seasons never share a month.
 *
 * @phpstan-type Season array{fromMonth: int, toMonth: int, amountCents: int}
 */
final class SeasonalAmounts
{
    /**
     * Checks what a client sent and returns it in a canonical shape.
     *
     * @return list<Season>
     *
     * @throws \InvalidArgumentException when a season is malformed or two overlap
     */
    public static function normalize(mixed $seasons): array
    {
        if (!is_array($seasons) || !array_is_list($seasons)) {
            throw new \InvalidArgumentException('seasonalAmounts must be a list of {fromMonth, toMonth, amountCents}.');
        }

        $normalized = [];
        $taken = [];
        foreach ($seasons as $season) {
            if (!is_array($season)) {
                throw new \InvalidArgumentException('seasonalAmounts must be a list of {fromMonth, toMonth, amountCents}.');
            }
            foreach (['fromMonth', 'toMonth', 'amountCents'] as $key) {
                if (!is_int($season[$key] ?? null)) {
                    throw new \InvalidArgumentException(sprintf('Each season needs an integer %s.', $key));
                }
            }
            if ($season['fromMonth'] < 1 || $season['fromMonth'] > 12 || $season['toMonth'] < 1 || $season['toMonth'] > 12) {
                throw new \InvalidArgumentException('A season runs between months 1 and 12.');
            }
            if ($season['amountCents'] < 0) {
                throw new \InvalidArgumentException('A season amount is a positive amount in cents.');
            }

            $season = ['fromMonth' => $season['fromMonth'], 'toMonth' => $season['toMonth'], 'amountCents' => $season['amountCents']];
            foreach (self::months($season) as $month) {
                if (isset($taken[$month])) {
                    throw new \InvalidArgumentException(sprintf('Two seasons overlap on month %d.', $month));
                }
                $taken[$month] = true;
            }
            $normalized[] = $season;
        }

        return $normalized;
    }

    /**
     * The season that covers `month`, if any.
     *
     * @param list<Season> $seasons
     *
     * @return Season|null
     */
    public static function covering(array $seasons, int $month): ?array
    {
        foreach ($seasons as $season) {
            if (in_array($month, self::months($season), true)) {
                return $season;
            }
        }

        return null;
    }

    /**
     * The months a season covers, in order: 11 → 2 is 11, 12, 1, 2.
     *
     * @param Season $season
     *
     * @return list<int>
     */
    public static function months(array $season): array
    {
        $months = [];
        for ($month = $season['fromMonth'];; $month = $month % 12 + 1) {
            $months[] = $month;
            if ($month === $season['toMonth']) {
                return $months;
            }
        }
    }
}
