<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** How one exercise of a session felt, from the user (MAG-775): what the coach progresses on. */
enum ExerciseFeeling: string
{
    case TooEasy = 'too_easy';
    case Right = 'right';
    case TooHard = 'too_hard';

    /** The French label, identical in the admin and on Android. */
    public function label(): string
    {
        return match ($this) {
            self::TooEasy => 'Trop facile',
            self::Right => 'Juste bien',
            self::TooHard => 'Trop dur',
        };
    }
}
