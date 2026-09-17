<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum ObligationFlag: string
{
    case Mandatory = 'mandatory';
    case Optional = 'optional';
    case Saving = 'saving';
    case Investment = 'investment';
    /**
     * Money coming in. Not an obligation at all, but the same field has to
     * answer "what kind of line is this" for a category, and a salary filed
     * as a mandatory expense would be a lie every report reads.
     */
    case Income = 'income';
}
