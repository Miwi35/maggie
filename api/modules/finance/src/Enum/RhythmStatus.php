<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Where spending stands against the pace the budget expects at a date. */
enum RhythmStatus: string
{
    case OnTrack = 'on_track';
    case Above = 'above';
    /** Within the pace on what is classified, but debits are not: no verdict is given (MAG-643). */
    case Incomplete = 'incomplete';

    public function label(): string
    {
        return match ($this) {
            self::OnTrack => 'Dans le rythme',
            self::Above => 'Au-dessus du rythme',
            self::Incomplete => 'Rythme incomplet',
        };
    }
}
