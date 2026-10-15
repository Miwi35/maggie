<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Where spending stands against the pace the budget expects at a date. */
enum RhythmStatus: string
{
    case OnTrack = 'on_track';
    case Above = 'above';

    public function label(): string
    {
        return match ($this) {
            self::OnTrack => 'Dans le rythme',
            self::Above => 'Au-dessus du rythme',
        };
    }
}
