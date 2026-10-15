<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\Service\TransactionMatcher;

/**
 * Whether a rule claims a transaction: its criteria recognise it. A
 * category has no nature to agree with the sign of the amount, so a refund
 * can be filed under "Loisirs" like any other line.
 *
 * The one place that answers it, for filing a line, applying a rule to the
 * history and previewing a rule that is not saved yet.
 */
final class TransactionMatchesRule
{
    public function __construct(
        private readonly TransactionMatcher $matcher,
    ) {
    }

    public function isSatisfiedBy(CategorizationRule $rule, Transaction $transaction): bool
    {
        return $rule->isActive()
            && $this->matches($rule->matchCriteria(), $transaction);
    }

    public function matches(MatchCriteria $criteria, Transaction $transaction): bool
    {
        return $this->matcher->matches($criteria, $transaction);
    }
}
