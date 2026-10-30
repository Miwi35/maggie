<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Where a day's steps and active energy come from (MAG-708): Health Connect on the phone, for now. */
enum DailyActivitySource: string
{
    case HealthConnect = 'health_connect';
}
