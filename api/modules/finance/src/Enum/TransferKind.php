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
}
