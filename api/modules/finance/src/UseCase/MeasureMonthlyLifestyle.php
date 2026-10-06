<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * What a month actually costs to live, measured and never declared: the
 * average month consumed over the recent past, loan payments excluded —
 * those are counted on their own by the debt timeline.
 *
 * It is the denominator of two different answers — the net saving capacity
 * and the independence counter — so it lives on its own: two copies of this
 * rule would mean two trains de vie, and the user would have to guess which
 * screen to believe.
 */
class MeasureMonthlyLifestyle
{
    public const SAMPLE_MONTHS = 3;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * The months the measure reads: the whole sample before the month given,
     * which defaults to the current one. The month in progress is left out —
     * half a month of spending would halve the figure.
     *
     * Midnight, not the current time: `bookedAt` is a date at 00:00, so a
     * window opened at 16 h would drop the 1st of the oldest month and take in
     * the 1st of the current one — a day of spending moved from one end of the
     * sample to the other, and a figure that changes during the day.
     *
     * @return array{0: \DateTimeImmutable, 1: \DateTimeImmutable} half-open [from, until)
     */
    public static function sampleWindow(?\DateTimeImmutable $thisMonth = null): array
    {
        $until = $thisMonth ?? new \DateTimeImmutable('midnight first day of this month');

        return [$until->modify(sprintf('-%d months', self::SAMPLE_MONTHS)), $until];
    }

    /** The average month consumed over the sample, in cents. */
    public function execute(User $user, ?\DateTimeImmutable $thisMonth = null): int
    {
        [$from, $until] = self::sampleWindow($thisMonth);

        return intdiv(
            $this->transactionRepository->sumConsumedBetween($user, $from, $until),
            self::SAMPLE_MONTHS,
        );
    }
}
