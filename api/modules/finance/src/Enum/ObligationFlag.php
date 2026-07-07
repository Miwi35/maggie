<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum ObligationFlag: string
{
    case Mandatory = 'mandatory';
    case Optional = 'optional';
    case Saving = 'saving';
    case Investment = 'investment';
}
