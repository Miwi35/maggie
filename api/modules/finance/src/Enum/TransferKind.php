<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What kind of internal movement a transaction is. Every aggregate of
 * `TransactionRepository` keeps only `None`, so the exclusion stays one rule
 * instead of a list that grows with each case.
 */
enum TransferKind: string
{
    case None = 'none';
    case Internal = 'internal';
}
