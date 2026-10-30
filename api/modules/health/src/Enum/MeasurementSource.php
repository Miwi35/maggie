<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** Where a body measurement comes from (MAG-706): typed by hand, or imported. */
enum MeasurementSource: string
{
    case Manual = 'manual';
    case HealthConnect = 'health_connect';
    case Withings = 'withings';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::Manual => 'Saisie',
            self::HealthConnect => 'Health Connect',
            self::Withings => 'Withings',
        };
    }
}
