<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** How a food intake was recorded (MAG-748): « Mangé » on a planned meal, typed by hand, or told to Maggie. */
enum FoodIntakeSource: string
{
    case Meal = 'meal';
    case Manual = 'manual';
    case Conversation = 'conversation';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::Meal => 'Repas',
            self::Manual => 'Saisie',
            self::Conversation => 'Maggie',
        };
    }
}
