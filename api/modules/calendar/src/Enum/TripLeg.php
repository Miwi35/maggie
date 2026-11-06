<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * Which way a trip goes (MAG-734): `outbound` to the event, or `return` from it to the home
 * at the end of a stay. An event has one trip per leg at most.
 */
enum TripLeg: string
{
    case Outbound = 'outbound';
    case Return = 'return';
}
