<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Specification\TransactionMatchesRule;

/**
 * Files a transaction under the category of the first rule that claims it.
 * A category set by hand, or inherited from the series the line settles, is
 * never overwritten.
 */
class CategorizeTransaction
{
    public function __construct(
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly TransactionMatchesRule $matchesRule,
    ) {
    }

    /**
     * The rule that claims this transaction, if any. A rule whose category
     * contradicts the sign of the amount — a "Loisirs" rule meeting a refund —
     * claims nothing: the line would be refused on write.
     */
    public function match(Transaction $transaction): ?CategorizationRule
    {
        return $this->winner($transaction, $this->ruleRepository->findActiveForUser($transaction->getUser()));
    }

    /**
     * The first of these rules to claim the transaction.
     *
     * @param iterable<CategorizationRule> $rules in the order they get their say: highest priority first
     */
    public function winner(Transaction $transaction, iterable $rules): ?CategorizationRule
    {
        foreach ($rules as $rule) {
            if ($this->matchesRule->isSatisfiedBy($rule, $transaction)) {
                return $rule;
            }
        }

        return null;
    }

    /** Whether the rules may still file this line: it has no category, and none was removed by hand. */
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

        $transaction->assignCategory($rule->getCategory(), CategorySource::Rule);

        return true;
    }
}
