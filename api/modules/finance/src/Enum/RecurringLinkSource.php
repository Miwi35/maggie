<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * Who decided whether a transaction belongs to a recurring operation. The
 * automatic attachment never touches a `manual` line, attached or detached.
 */
enum RecurringLinkSource: string
{
    case Auto = 'auto';
    case Manual = 'manual';
}
