<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Grocery\Enum\Unit;

/**
 * How often a product is bought, read from the past errands (MAG-495): the
 * median gap between two purchases and the median quantity. Deterministic,
 * no model: three purchases at least, or there is no rhythm to speak of.
 *
 * A day counts once: two errands the same day (two stores) are one purchase,
 * their quantities added — a gap of zero days would halve the rhythm.
 */
final readonly class PurchaseRhythm
{
    public const MIN_PURCHASES = 3;

    private function __construct(
        public int $everyDays,
        public \DateTimeImmutable $lastBoughtOn,
        public ?float $usualQuantity,
        public ?Unit $usualUnit,
    ) {
    }

    /**
     * @param list<array{day: \DateTimeImmutable, quantity: ?float, unit: ?Unit}> $purchases calendar days at midnight, in any order
     */
    public static function of(array $purchases): ?self
    {
        /** @var array<string, array{day: \DateTimeImmutable, lines: list<array{quantity: ?float, unit: ?Unit}>}> $byDay */
        $byDay = [];
        foreach ($purchases as $purchase) {
            $key = $purchase['day']->format('Y-m-d');
            $byDay[$key] ??= ['day' => $purchase['day'], 'lines' => []];
            $byDay[$key]['lines'][] = ['quantity' => $purchase['quantity'], 'unit' => $purchase['unit']];
        }

        if (\count($byDay) < self::MIN_PURCHASES) {
            return null;
        }

        ksort($byDay);
        $days = array_values($byDay);

        $gaps = [];
        for ($i = 1, $n = \count($days); $i < $n; ++$i) {
            $gaps[] = (int) $days[$i - 1]['day']->diff($days[$i]['day'])->days;
        }
        $everyDays = max(1, (int) round(self::median($gaps)));

        $last = $days[\count($days) - 1];
        // The unit the product was last bought in; the quantity is the median of the days bought in it.
        $usualUnit = null;
        foreach ($last['lines'] as $line) {
            $usualUnit ??= $line['unit'];
        }

        $quantities = [];
        foreach ($days as $day) {
            $sum = null;
            foreach ($day['lines'] as $line) {
                if (null !== $line['quantity'] && $line['unit'] === $usualUnit) {
                    $sum = ($sum ?? 0.0) + $line['quantity'];
                }
            }
            if (null !== $sum) {
                $quantities[] = $sum;
            }
        }

        return new self(
            everyDays: $everyDays,
            lastBoughtOn: $last['day'],
            usualQuantity: [] === $quantities ? null : self::median($quantities),
            usualUnit: $usualUnit,
        );
    }

    /** The day it is wanted again: the last purchase plus the usual gap. */
    public function dueOn(): \DateTimeImmutable
    {
        return $this->lastBoughtOn->modify(sprintf('+%d days', $this->everyDays));
    }

    public function isDueOn(\DateTimeImmutable $today): bool
    {
        return $this->dueOn()->format('Y-m-d') <= $today->format('Y-m-d');
    }

    /** @param non-empty-list<int|float> $values */
    private static function median(array $values): float
    {
        sort($values);
        $count = \count($values);
        $middle = intdiv($count, 2);

        return 0 === $count % 2
            ? ($values[$middle - 1] + $values[$middle]) / 2
            : (float) $values[$middle];
    }
}
