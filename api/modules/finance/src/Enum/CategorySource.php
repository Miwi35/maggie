<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** How a transaction got its category. */
enum CategorySource: string
{
    case None = 'none';
    case Manual = 'manual';
    case Rule = 'rule';
    /** Inherited from the recurring operation the transaction is attached to. */
    case Series = 'series';
}
