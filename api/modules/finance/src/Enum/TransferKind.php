<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What kind of internal movement a transaction is.
 *
 * A movement between two of the owner's own accounts is neither an expense nor
 * an income: every aggregate of `TransactionRepository` keeps only `None`, so
 * the exclusion is one rule instead of a list that grows with each case.
 */
enum TransferKind: string
{
    case None = 'none';
    case Internal = 'internal';
}
