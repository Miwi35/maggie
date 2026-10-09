<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Where the reference amount comes from, and so whether it may move. */
enum ReferenceAmountSource: string
{
    /** Stated by the owner: it stays as typed. */
    case Declared = 'declared';
    /** Read from the transactions: it follows the latest ones. */
    case Measured = 'measured';
}
