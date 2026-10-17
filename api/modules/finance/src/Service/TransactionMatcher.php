<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Import\MerchantExtractor;

/**
 * The one engine that recognises a transaction, for every operation, with or
 * without recurrence: counterparty, then label, then
 * direction and amount bounds — which recognise a transaction alone when the
 * criteria name no payee and no label.
 *
 * Recognising a payee is not attaching a transaction to a series: the date
 * window and the amount tolerance of a recurring operation stay its own
 * conditions.
 */
class TransactionMatcher
{
    public function matches(MatchCriteria $criteria, Transaction $transaction): bool
    {
        // The amount alone would claim an internal transfer or a rejected
        // payment of the same size: those are neither income nor expense.
        if (!$criteria->hasIdentity() && TransferKind::None !== $transaction->getTransferKind()) {
            return false;
        }

        return $this->matchesIdentity($criteria, $transaction)
            && $this->matchesAmount($criteria, $transaction->getAmountCents());
    }

    /**
     * Two counterparties settle it, either way: the bank never rewrites the
     * creditor, while it rewrites the label every month. Without one on either
     * side, the label pattern decides, and failing that the expected
     * counterparty read in the folded label.
     */
    private function matchesIdentity(MatchCriteria $criteria, Transaction $transaction): bool
    {
        $expected = $criteria->counterpartyKey;
        $actual = $transaction->getCounterpartyKey();

        if (null !== $expected && '' !== $expected && null !== $actual) {
            return $expected === $actual;
        }

        if (null !== $criteria->labelPattern && '' !== $criteria->labelPattern) {
            return $this->matchesLabel($criteria->labelPattern, $criteria->matchType, $transaction->getLabel());
        }

        if (null !== $expected && '' !== $expected) {
            // Whole words: a three-letter payee must not match inside another word.
            return 1 === preg_match('/(^| )'.preg_quote($expected, '/').'( |$)/u', MerchantExtractor::key($transaction->getLabel()));
        }

        // No identity: the direction and the amount bounds recognise it alone.
        // Nothing to recognise by at all would claim every transaction.
        return $criteria->hasDirectedAmount();
    }

    private function matchesLabel(string $pattern, MatchType $matchType, string $label): bool
    {
        $haystack = mb_strtolower($label);
        $needle = mb_strtolower($pattern);

        return match ($matchType) {
            MatchType::Contains => '' !== $needle && str_contains($haystack, $needle),
            MatchType::StartsWith => '' !== $needle && str_starts_with($haystack, $needle),
            MatchType::Equals => $haystack === $needle,
        };
    }

    private function matchesAmount(MatchCriteria $criteria, int $amountCents): bool
    {
        if (AmountDirection::Debit === $criteria->direction && $amountCents >= 0) {
            return false;
        }

        if (AmountDirection::Credit === $criteria->direction && $amountCents <= 0) {
            return false;
        }

        $absolute = abs($amountCents);

        if (null !== $criteria->minAmountCents && $absolute < $criteria->minAmountCents) {
            return false;
        }

        return null === $criteria->maxAmountCents || $absolute <= $criteria->maxAmountCents;
    }
}
