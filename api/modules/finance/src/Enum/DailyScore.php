<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** The daily signal: where the month stands, without passing judgement. */
enum DailyScore: string
{
    case Green = 'green';
    case Neutral = 'neutral';
    case Orange = 'orange';
    case Red = 'red';
}
