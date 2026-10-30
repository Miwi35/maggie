<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** The sex the Mifflin-St Jeor equation is given (MAG-707): + 5 for a man, − 161 for a woman. */
enum Sex: string
{
    case Male = 'male';
    case Female = 'female';

    /** The constant of the equation. */
    public function bmrConstant(): int
    {
        return match ($this) {
            self::Male => 5,
            self::Female => -161,
        };
    }
}
