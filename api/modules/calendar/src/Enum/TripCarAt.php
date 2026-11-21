<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/**
 * Where the user says the car is for a drive (MAG-929): where the phone last saw it
 * parked, or at the trip's origin. Unsaid, {@see \Maggie\Calendar\Service\TripPlanner} decides from the origin.
 */
enum TripCarAt: string
{
    case Parked = 'parked';
    case Origin = 'origin';
}
