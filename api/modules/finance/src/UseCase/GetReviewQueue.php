<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
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
    public const MAX_LIMIT = 200;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly GetTransactionsToSort $getTransactionsToSort,
    ) {
    }

    /**
     * Each item is a line of the « À qualifier » pile (with its suggestion)
     * or a recognised one, told apart by `recognised`; a recognised line
     * names who recognised it (`qualifiedBy` rule|series, with the id of the
     * operation) and the category it got.
     *
     * @return array{
     *     month: string,
     *     unrecognisedCount: int,
     *     unrecognisedCents: int,
     *     recognisedCount: int,
     *     items: list<array<string, mixed>>
     * }
     */
    public function execute(User $user, \DateTimeImmutable $monthStart, int $limit = self::DEFAULT_LIMIT): array
    {
        $limit = max(1, min($limit, self::MAX_LIMIT));

        // The unrecognised are not tied to the month: an old line still waiting comes first.
        $pile = $this->getTransactionsToSort->execute($user, $limit, true);
        $recognised = $this->transactionRepository->findRecognisedBetween(
            $user,
            $monthStart,
            $monthStart->modify('+1 month'),
            max(0, $limit - \count($pile['transactions'])),
        );

        $items = array_map(static fn (array $row): array => [
            'recognised' => false,
            ...$row,
            'categoryId' => null,
            'categoryPath' => null,
            'qualifiedBy' => null,
            'qualifiedBySeriesId' => null,
            'qualifiedByRuleId' => null,
        ],
            $pile['transactions'],
        );
        foreach ($recognised as $transaction) {
            $items[] = $this->recognisedItem($transaction);
        }

        return [
            'month' => $monthStart->format('Y-m'),
            'unrecognisedCount' => $pile['total'],
            'unrecognisedCents' => $pile['totalCents'],
            'recognisedCount' => \count($recognised),
            'items' => $items,
        ];
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
