<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Repository\CategorizationRuleRepository;

/**
 * Files a transaction under the category of the first rule that claims it.
 * A category set by hand is never overwritten.
 */
class CategorizeTransaction
{
    public function __construct(
        private readonly CategorizationRuleRepository $ruleRepository,
    ) {
    }

    /** The rule that claims this transaction, if any. */
    public function match(Transaction $transaction): ?CategorizationRule
    {
        foreach ($this->ruleRepository->findActiveForUser($transaction->getUser()) as $rule) {
            if ($rule->matches($transaction)) {
                return $rule;
            }
        }

        return null;
    }

    /** Applies the winning rule in place; true when the transaction was categorized. */
    public function apply(Transaction $transaction): bool
    {
        if ($transaction->getCategorySource() === CategorySource::Manual) {
            return false;
        }

        $rule = $this->match($transaction);
        if ($rule === null) {
            return false;
        }

        $transaction->assignCategory($rule->getCategory(), CategorySource::Rule);

        return true;
    }
}
