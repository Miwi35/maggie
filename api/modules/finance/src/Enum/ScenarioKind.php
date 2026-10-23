<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Where a scenario comes from (MAG-550): the user's own, one of the three automatic ones, or the advisor's suggestion. */
enum ScenarioKind: string
{
    case User = 'user';
    case Auto = 'auto';
    case Advisor = 'advisor';
}
