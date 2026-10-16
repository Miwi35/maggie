<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The lines an operation recognised in a month that the owner has not checked
 * yet, grouped by the operation that recognised them (« Courses · 6 »), the
 * lines of a group oldest first and the groups by their oldest line.
 */
class GetRecognisedToControl
{
    public const MAX_LINES = 500;
    private const DELETED_LABEL = 'Règle ou série supprimée';

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly GetReviewQueue $reviewQueue,
    ) {
    }

    /**
     * @return array{
     *     month: string,
     *     total: int,
     *     groups: list<array{
     *         operationType: string,
     *         operationId: string|null,
     *         label: string,
     *         count: int,
     *         totalCents: int,
     *         unratedCount: int,
     *         transactions: list<array<string, mixed>>
     *     }>
     * }
     */
    public function execute(User $user, \DateTimeImmutable $monthStart): array
    {
        $lines = $this->transactionRepository->findRecognisedBetween(
            $user,
            $monthStart,
            $monthStart->modify('+1 month'),
            self::MAX_LINES,
        );

        $groups = [];
        foreach ($lines as $transaction) {
            [$type, $operation] = $this->operationOf($transaction);
            $key = $type.':'.($operation?->getId() ?? 'deleted');
            $groups[$key] ??= [
                'operationType' => $type,
                'operationId' => null === $operation ? null : (string) $operation->getId(),
                'label' => null === $operation ? self::DELETED_LABEL : $this->labelOf($operation),
                'count' => 0,
                'totalCents' => 0,
                'unratedCount' => 0,
                'transactions' => [],
            ];
            ++$groups[$key]['count'];
            $groups[$key]['totalCents'] += $transaction->getAmountCents();
            if ($transaction->canBeJudged() && 'unrated' === $transaction->getRetrospect()->value) {
                ++$groups[$key]['unratedCount'];
            }
            $groups[$key]['transactions'][] = $this->reviewQueue->recognisedItem($transaction);
        }

        return [
            'month' => $monthStart->format('Y-m'),
            'total' => array_sum(array_column($groups, 'count')),
            'groups' => array_values($groups),
        ];
    }

    /**
     * The operation behind the line; null once it was deleted, while the line
     * keeps the category it gave it.
     *
     * @return array{string, RecurringOperation|CategorizationRule|null}
     */
    private function operationOf(Transaction $transaction): array
    {
        return match ($transaction->getCategorySource()) {
            CategorySource::Series => ['series', $transaction->getQualifiedBySeries()],
            default => ['rule', $transaction->getQualifiedByRule()],
        };
    }

    private function labelOf(RecurringOperation|CategorizationRule $operation): string
    {
        if ($operation instanceof RecurringOperation) {
            return $operation->getLabel();
        }

        return $operation->getLabelPattern() ?? $operation->getCategory()->getPath();
    }
}
