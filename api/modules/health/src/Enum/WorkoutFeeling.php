<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** How the workout felt, from the user (MAG-705). */
enum WorkoutFeeling: string
{
    case VeryGood = 'very_good';
    case Good = 'good';
    case Hard = 'hard';
    case VeryHard = 'very_hard';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::VeryGood => 'Très bien',
            self::Good => 'Bien',
            self::Hard => 'Dur',
            self::VeryHard => 'Très dur',
        };
    }
}
