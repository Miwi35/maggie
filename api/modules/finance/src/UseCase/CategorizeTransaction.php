<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Service\TransactionMatcher;
use Maggie\Finance\Service\TransactionNatureGuard;

/**
 * Files a transaction under the category of the first rule that claims it.
 * A category set by hand is never overwritten.
 */
class CategorizeTransaction
{
    public function __construct(
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly TransactionNatureGuard $natureGuard,
        private readonly TransactionMatcher $matcher,
    ) {
    }

    /**
     * The rule that claims this transaction, if any. A rule whose category
     * contradicts the sign of the amount — a "Loisirs" rule meeting a refund —
     * claims nothing: the line would be refused on write.
     */
    public function match(Transaction $transaction): ?CategorizationRule
    {
        foreach ($this->ruleRepository->findActiveForUser($transaction->getUser()) as $rule) {
            if ($rule->isActive()
                && $this->matcher->matches($rule->matchCriteria(), $transaction)
                && $this->natureGuard->isCompatible($transaction->getAmountCents(), $rule->getCategory())) {
                return $rule;
            }
        }

        return null;
    }

    /** Applies the winning rule in place; true when the transaction was categorized. */
    public function apply(Transaction $transaction): bool
    {
        if (CategorySource::Manual === $transaction->getCategorySource()) {
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
