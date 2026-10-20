<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** How much a project counts for the user's wellbeing (MAG-519): its score weighs it in the planning. */
enum ProjectWellbeing: string
{
    case Essential = 'essential';
    case High = 'high';
    case Medium = 'medium';
    case Comfort = 'comfort';

    /** 4 for essential down to 1 for comfort. */
    public function score(): int
    {
        return match ($this) {
            self::Essential => 4,
            self::High => 3,
            self::Medium => 2,
            self::Comfort => 1,
        };
    }
}
