<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Whether something is left to give back on a debt: computed, never stored. */
enum PeerDebtStatus: string
{
    case Open = 'open';
    case Settled = 'settled';
}
