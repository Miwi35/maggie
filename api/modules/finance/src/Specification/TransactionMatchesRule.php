<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Service\MatchCriteria;
use Maggie\Finance\Service\TransactionMatcher;
use Maggie\Finance\Service\TransactionNatureGuard;

/**
 * Whether a rule claims a transaction: the criteria recognise it and the
 * rule's category does not contradict the sign of its amount — a "Loisirs"
 * rule meeting a refund claims nothing, the line would be refused on write.
 *
 * The one place that answers it, for filing a line, applying a rule to the
 * history and previewing a rule that is not saved yet.
 */
final class TransactionMatchesRule
{
    public function __construct(
        private readonly TransactionMatcher $matcher,
        private readonly TransactionNatureGuard $natureGuard,
    ) {
    }

    public function isSatisfiedBy(CategorizationRule $rule, Transaction $transaction): bool
    {
        return $rule->isActive()
            && $this->matches($rule->matchCriteria(), $rule->getCategory(), $transaction);
    }

    /** @param ?Category $category null while a rule is being drafted and has none yet: only the criteria count */
    public function matches(MatchCriteria $criteria, ?Category $category, Transaction $transaction): bool
    {
        return $this->matcher->matches($criteria, $transaction)
            && (null === $category || $this->natureGuard->isCompatible($transaction->getAmountCents(), $category));
    }
}
