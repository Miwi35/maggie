<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * Who chose a trip's origin (MAG-654): the user, in an answer the evening
 * before, or Maggie. A choice of the user is kept: only the durations are
 * computed again.
 */
enum TripDecider: string
{
    case User = 'user';
    case Maggie = 'maggie';
}
