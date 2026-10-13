<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What kind of neutral movement a transaction is. Every aggregate of
 * `TransactionRepository` keeps only `None`, so the exclusion stays one rule
 * instead of a list that grows with each case.
 */
enum TransferKind: string
{
    case None = 'none';
    /** Money moved between two of the owner's own accounts. */
    case Internal = 'internal';
    /**
     * A payment the bank rejected, and the credit that cancels it, on the same
     * account: a payment that did not happen (MAG-350).
     */
    case Rejected = 'rejected';
    /**
     * An internal transfer from a savings or investment account to a checking
     * one while the salary is late: money the owner owes back to that source.
     */
    case Advance = 'advance';
    /** The transfer that gives an advance back; its debit leg points at it with `repays`. */
    case Repayment = 'repayment';
    /** A regular top-up the owner marked: never taken for a repayment. */
    case Funding = 'funding';

    /** A movement between two different accounts of the owner, whatever its purpose. */
    public function isBetweenAccounts(): bool
    {
        return self::None !== $this && self::Rejected !== $this;
    }
}
