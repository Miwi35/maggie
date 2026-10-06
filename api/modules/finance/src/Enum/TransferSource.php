<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * Who decided what a transaction's `transferKind` is.
 *
 * Plays exactly the role `CategorySource` plays for the category: the
 * detection never overwrites `Manual`, just as `findUncategorizedForUser`
 * leaves alone what `CategorySource::Manual` settled.
 */
enum TransferSource: string
{
    case Auto = 'auto';
    case Manual = 'manual';
}
