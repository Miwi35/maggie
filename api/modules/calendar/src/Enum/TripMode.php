<?php

declare(strict_types=1);

namespace Maggie\Calendar\Enum;

/** How the user goes to an event (« Partir à l'heure », MAG-653), and the kind of a trip's step. */
enum TripMode: string
{
    case Drive = 'drive';
    case Transit = 'transit';
    case Walk = 'walk';
    case Bicycle = 'bicycle';

    /** Google Routes' `travelMode`. */
    public function routesTravelMode(): string
    {
        return match ($this) {
            self::Drive => 'DRIVE',
            self::Transit => 'TRANSIT',
            self::Walk => 'WALK',
            self::Bicycle => 'BICYCLE',
        };
    }

    /** Google Maps' `travelmode` in a directions link. */
    public function mapsTravelMode(): string
    {
        return match ($this) {
            self::Drive => 'driving',
            self::Transit => 'transit',
            self::Walk => 'walking',
            self::Bicycle => 'bicycling',
        };
    }
}
