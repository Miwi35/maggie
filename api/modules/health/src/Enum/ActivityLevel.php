<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** How active a day is (MAG-707): the factor the basal metabolism is multiplied by to give the day's need. */
enum ActivityLevel: string
{
    case Sedentary = 'sedentary';
    case Light = 'light';
    case Moderate = 'moderate';
    case Active = 'active';
    case VeryActive = 'very_active';

    public function factor(): float
    {
        return match ($this) {
            self::Sedentary => 1.2,
            self::Light => 1.375,
            self::Moderate => 1.55,
            self::Active => 1.725,
            self::VeryActive => 1.9,
        };
    }

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::Sedentary => 'Sédentaire',
            self::Light => 'Légère',
            self::Moderate => 'Modérée',
            self::Active => 'Active',
            self::VeryActive => 'Très active',
        };
    }
}
