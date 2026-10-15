<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Specification\TransactionMatchesRule;

/**
 * Gives a transaction the qualification of the first operation without
 * recurrence that claims it — what a categorization rule did. A qualification
 * set by hand, or inherited from the series the line settles, is never
 * overwritten: the series attach their lines first.
 */
class CategorizeTransaction
{
    public function __construct(
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionMatchesRule $matchesRule,
    ) {
    }

    /**
     * The operation without recurrence that claims this transaction, if any.
     */
    public function match(Transaction $transaction): ?RecurringOperation
    {
        return $this->winner($transaction, $this->operationRepository->findActiveWithoutRecurrenceForUser($transaction->getUser()));
    }

    /**
     * The first of these operations to claim the transaction.
     *
     * @param iterable<RecurringOperation> $rules in the order they get their say: highest priority first
     */
    public function winner(Transaction $transaction, iterable $rules): ?RecurringOperation
    {
        foreach ($rules as $rule) {
            if ($this->matchesRule->isSatisfiedBy($rule, $transaction)) {
                return $rule;
            }
        }

        return null;
    }

    /** Whether the operations without recurrence may still file this line: it has no category, and none was removed by hand. */
    public function awaitsCategory(Transaction $transaction): bool
    {
        return null === $transaction->getCategory() && CategorySource::Manual !== $transaction->getCategorySource();
    }

    /** Applies the winning rule in place; true when the transaction was categorized. */
    public function apply(Transaction $transaction): bool
    {
        if (CategorySource::Manual === $transaction->getCategorySource()) {
            return false;
        }

        // The category of the series a line settles wins over any rule.
        if (CategorySource::Series === $transaction->getCategorySource() && null !== $transaction->getRecurringOperation()) {
            return false;
        }

        $rule = $this->match($transaction);
        if (null === $rule) {
            return false;
        }

        $transaction->qualify($rule->getQualification(), $rule);

        return true;
    }
}
