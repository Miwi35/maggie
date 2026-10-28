<?php

declare(strict_types=1);

namespace Maggie\Core\Enum;

/**
 * How the phone obtained a position (MAG-666): the satellites, or Wi-Fi and cell towers. The two
 * are kept apart because a network fix can be kilometres off while still claiming a few hundred metres.
 */
enum LocationSource: string
{
    case Gps = 'gps';
    case Network = 'network';

    /** A fix at least this precise (metres) is taken for a GPS one when its source is not said. */
    public const float GPS_GRADE_METERS = 50.0;

    public static function fromAccuracy(float $accuracy): self
    {
        return $accuracy > 0.0 && $accuracy <= self::GPS_GRADE_METERS ? self::Gps : self::Network;
    }
}
