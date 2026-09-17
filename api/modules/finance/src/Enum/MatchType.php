<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum MatchType: string
{
    case Contains = 'contains';
    case StartsWith = 'starts_with';
    case Equals = 'equals';
}
