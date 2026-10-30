<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/**
 * Where a workout comes from (MAG-705). `planned` is a session still to do:
 * the clients leave it out of what was done.
 */
enum WorkoutSource: string
{
    case Manual = 'manual';
    case Watch = 'watch';
    case Activity = 'activity';
    case Estimated = 'estimated';
    case Planned = 'planned';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::Manual => 'Saisie',
            self::Watch => 'Montre',
            self::Activity => 'Activité Sport',
            self::Estimated => 'Estimée',
            self::Planned => 'Prévue',
        };
    }
}
