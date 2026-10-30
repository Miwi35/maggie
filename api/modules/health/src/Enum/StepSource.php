<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Where the day's steps are read from (MAG-707): Health Connect on the phone, or nowhere. */
enum StepSource: string
{
    case HealthConnect = 'health_connect';
    case None = 'none';
}
