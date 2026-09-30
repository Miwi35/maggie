<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The monthly look back: what was spent outside the essentials, what of it
 * the user would skip next time, and how that compares with recent months.
 *
 * Nothing is stored: the verdicts on the transactions are the durable data,
 * the review is read from them.
 */
class GetMonthlyReview
{
    private const RECENT_MONTHS = 3;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $start = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $end = $start->modify('+1 month');

        $reviewable = $this->transactionRepository->findReviewableBetween($user, $start, $end);

        $keptCents = 0;
        $avoidableCents = 0;
        $unratedCents = 0;
        $pending = [];

        foreach ($reviewable as $transaction) {
            $amount = abs($transaction->getAmountCents());

            match ($transaction->getRetrospect()) {
                RetrospectVerdict::Keep => $keptCents += $amount,
                RetrospectVerdict::Avoidable => $avoidableCents += $amount,
                RetrospectVerdict::Unrated => $unratedCents += $amount,
            };

            if (RetrospectVerdict::Unrated === $transaction->getRetrospect()) {
                $pending[] = $this->serialize($transaction);
            }
        }

        $reviewableCents = $keptCents + $avoidableCents + $unratedCents;
        $ratedCents = $keptCents + $avoidableCents;

        return [
            'year' => $year,
            'month' => $month,
            'reviewableCents' => $reviewableCents,
            'keptCents' => $keptCents,
            'avoidableCents' => $avoidableCents,
            'unratedCents' => $unratedCents,
            'ratedCount' => \count($reviewable) - \count($pending),
            'pendingCount' => \count($pending),
            // Share of what was judged that is worth keeping. A month nobody
            // has looked at has no score at all — it is not a zero.
            'optimisationScore' => $ratedCents > 0
                ? (int) round($keptCents / $ratedCents * 100)
                : null,
            'isComplete' => [] === $pending && [] !== $reviewable,
            'pending' => $pending,
            'comparison' => $this->compare($user, $start),
        ];
    }

    /**
     * @return array{thisMonthCents: int, previousMonthCents: int, recentAverageCents: int, sameMonthLastYearCents: int}
     */
    private function compare(User $user, \DateTimeImmutable $start): array
    {
        $consumedOver = fn (\DateTimeImmutable $from, \DateTimeImmutable $until): int => $this
            ->transactionRepository->sumConsumedBetween($user, $from, $until);

        $previousStart = $start->modify('-1 month');
        $recentStart = $start->modify(sprintf('-%d months', self::RECENT_MONTHS));
        $lastYearStart = $start->modify('-1 year');

        return [
            'thisMonthCents' => $consumedOver($start, $start->modify('+1 month')),
            'previousMonthCents' => $consumedOver($previousStart, $start),
            'recentAverageCents' => intdiv($consumedOver($recentStart, $start), self::RECENT_MONTHS),
            'sameMonthLastYearCents' => $consumedOver($lastYearStart, $lastYearStart->modify('+1 month')),
        ];
    }

    /** @return array<string, mixed> */
    private function serialize(Transaction $transaction): array
    {
        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'categoryId' => null !== $transaction->getCategory()
                ? (string) $transaction->getCategory()->getId()
                : null,
            'categoryName' => $transaction->getCategory()?->getName(),
            'retrospect' => $transaction->getRetrospect()->value,
        ];
    }
}
