<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\ReviewProgressRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The line the monthly review walks, one transaction at a time: the lines no
 * operation recognised first (they wait for the owner to qualify them), then
 * the ones an operation recognised in the month, each group oldest first.
 *
 * Nothing is stored: a line leaves the first group when it gets a category,
 * so resuming the review elsewhere offers only what is left.
 */
class GetReviewQueue
{
    public const DEFAULT_LIMIT = 100;
    public const MAX_LIMIT = 1000;

    public const PERIOD_MONTH = 'month';
    public const PERIOD_SINCE_REVIEW = 'since_review';
    public const PERIOD_ALL = 'all';
    public const PERIODS = [self::PERIOD_MONTH, self::PERIOD_SINCE_REVIEW, self::PERIOD_ALL];

    public const STATE_ALL = 'all';
    public const STATE_UNRECOGNISED = 'unrecognised';
    public const STATE_RECOGNISED = 'recognised';
    public const STATES = [self::STATE_ALL, self::STATE_UNRECOGNISED, self::STATE_RECOGNISED];

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly ReviewProgressRepository $reviewProgressRepository,
        private readonly GetTransactionsToSort $getTransactionsToSort,
    ) {
    }

    /**
     * Each item is a line of the « À qualifier » pile (with its suggestion)
     * or a recognised one, told apart by `recognised`; a recognised line
     * names who recognised it (`qualifiedBy` rule|series, with the id of the
     * operation and its label in `operationLabel`) and the category it got.
     *
     * Without a `$period` (the monthly review activity, MAG-456) the pile is
     * not tied to a date and the recognised are the month's. With one (MAG-539)
     * the date bound applies to both groups: `month` (the month), `since_review`
     * (strictly after the day the last review was completed) or `all`. The
     * counts are those of the period whatever the `$state` asked; `total` is
     * what the `$state` keeps.
     *
     * @return array{
     *     month: string,
     *     period: string|null,
     *     sinceReviewDate: string|null,
     *     unrecognisedCount: int,
     *     unrecognisedCents: int,
     *     recognisedCount: int,
     *     total: int,
     *     items: list<array<string, mixed>>
     * }
     */
    public function execute(
        User $user,
        \DateTimeImmutable $monthStart,
        int $limit = self::DEFAULT_LIMIT,
        ?string $period = null,
        string $state = self::STATE_ALL,
    ): array {
        $limit = max(1, min($limit, self::MAX_LIMIT));

        // Legacy call: the pile is not bounded by date, the recognised are the month's.
        $pileFrom = $pileUntil = null;
        $recognisedFrom = $monthStart;
        $recognisedUntil = $monthStart->modify('+1 month');
        $sinceReviewDate = null;

        if (null !== $period) {
            $from = $until = null;
            if (self::PERIOD_MONTH === $period) {
                [$from, $until] = [$recognisedFrom, $recognisedUntil];
            } elseif (self::PERIOD_SINCE_REVIEW === $period) {
                $completedAt = $this->reviewProgressRepository->findLastCompletedAt($user);
                if (null !== $completedAt) {
                    $sinceReviewDate = $completedAt->format('Y-m-d');
                    $from = (new \DateTimeImmutable($sinceReviewDate))->modify('+1 day');
                }
            }
            [$pileFrom, $pileUntil, $recognisedFrom, $recognisedUntil] = [$from, $until, $from, $until];
        }

        // The unrecognised come first: an old line still waiting leads.
        $pile = $this->transactionRepository->findToSortBetween(
            $user,
            $pileFrom,
            $pileUntil,
            self::STATE_RECOGNISED === $state ? 1 : $limit,
        );
        $recognisedCount = $this->transactionRepository->countRecognisedBetween($user, $recognisedFrom, $recognisedUntil);

        $items = [];
        if (self::STATE_RECOGNISED !== $state) {
            $context = $this->getTransactionsToSort->suggestionContext($user);
            foreach ($pile['transactions'] as $transaction) {
                $items[] = [
                    'recognised' => false,
                    ...$this->getTransactionsToSort->row($transaction, $context['rules'], $context['categoriesByName']),
                    'categoryId' => null,
                    'categoryPath' => null,
                    'qualifiedBy' => null,
                    'qualifiedBySeriesId' => null,
                    'qualifiedByRuleId' => null,
                    'operationLabel' => null,
                ];
            }
        }
        if (self::STATE_UNRECOGNISED !== $state) {
            $recognised = $this->transactionRepository->findRecognisedBetween(
                $user,
                $recognisedFrom,
                $recognisedUntil,
                max(0, $limit - \count($items)),
            );
            foreach ($recognised as $transaction) {
                $items[] = [...$this->recognisedItem($transaction), 'operationLabel' => $this->operationLabel($transaction)];
            }
        }

        return [
            'month' => $monthStart->format('Y-m'),
            'period' => $period,
            'sinceReviewDate' => $sinceReviewDate,
            'unrecognisedCount' => $pile['total'],
            'unrecognisedCents' => $pile['totalCents'],
            'recognisedCount' => $recognisedCount,
            'total' => match ($state) {
                self::STATE_UNRECOGNISED => $pile['total'],
                self::STATE_RECOGNISED => $recognisedCount,
                default => $pile['total'] + $recognisedCount,
            },
            'items' => $items,
        ];
    }

    /** The label of the rule or series that recognised the line; null once it was deleted. */
    public function operationLabel(Transaction $transaction): ?string
    {
        $operation = CategorySource::Series === $transaction->getCategorySource()
            ? $transaction->getQualifiedBySeries()
            : $transaction->getQualifiedByRule();

        return null === $operation ? null : self::labelOf($operation);
    }

    public static function labelOf(RecurringOperation $operation): string
    {
        return $operation->getLabel() ?? $operation->getLabelPattern() ?? $operation->getCategory()->getPath();
    }

    /** @return array<string, mixed> */
    public function recognisedItem(Transaction $transaction): array
    {
        $category = $transaction->getCategory();

        return [
            'recognised' => true,
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'counterpartyName' => $transaction->getCounterpartyName(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'accountId' => (string) $transaction->getAccount()->getId(),
            'accountName' => $transaction->getAccount()->getName(),
            'judgeable' => $transaction->canBeJudged(),
            'retrospect' => $transaction->getRetrospect()->value,
            'suggestion' => null,
            'categoryId' => null === $category ? null : (string) $category->getId(),
            'categoryPath' => $category?->getPath(),
            'qualifiedBy' => $transaction->getQualifiedBy(),
            'qualifiedBySeriesId' => null === $transaction->getQualifiedBySeries() ? null : (string) $transaction->getQualifiedBySeries()->getId(),
            'qualifiedByRuleId' => null === $transaction->getQualifiedByRule() ? null : (string) $transaction->getQualifiedByRule()->getId(),
        ];
    }
}
