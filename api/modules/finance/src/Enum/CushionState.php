<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

enum CushionState: string
{
    /** The target has never been reached. */
    case Building = 'building';
    /** The cushion covers its target. */
    case Complete = 'complete';
    /** The target was reached once, then dipped into. */
    case Recharging = 'recharging';
}
