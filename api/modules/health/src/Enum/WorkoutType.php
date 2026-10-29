<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** What kind of workout it was (MAG-705). */
enum WorkoutType: string
{
    case Run = 'run';
    case Walk = 'walk';
    case Bike = 'bike';
    case Swim = 'swim';
    case Strength = 'strength';
    case Climbing = 'climbing';
    case Other = 'other';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::Run => 'Course à pied',
            self::Walk => 'Marche',
            self::Bike => 'Vélo',
            self::Swim => 'Natation',
            self::Strength => 'Musculation',
            self::Climbing => 'Escalade',
            self::Other => 'Autre',
        };
    }
}
