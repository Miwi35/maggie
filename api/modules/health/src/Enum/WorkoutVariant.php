<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** What kind of coach session it is (MAG-775): a regular one, a test of the levels, a lighter one, a deload week's. */
enum WorkoutVariant: string
{
    case Normal = 'normal';
    case Calibration = 'calibration';
    case Soft = 'soft';
    case Deload = 'deload';
}
