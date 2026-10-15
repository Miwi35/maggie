<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** The daily signal: where the month stands, without passing judgement. */
enum DailyScore: string
{
    case Green = 'green';
    case Neutral = 'neutral';
    case Orange = 'orange';
    case Red = 'red';

    public function label(): string
    {
        return match ($this) {
            self::Green => 'Dans le vert',
            self::Neutral => 'Dans les clous',
            self::Orange => 'Attention',
            self::Red => 'Dépassement',
        };
    }

    /** How bad it is: the global score is the most degraded of its parts. */
    public function severity(): int
    {
        return match ($this) {
            self::Green => 0,
            self::Neutral => 1,
            self::Orange => 2,
            self::Red => 3,
        };
    }
}
