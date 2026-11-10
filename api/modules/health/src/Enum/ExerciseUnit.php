<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/** What an exercise target counts (MAG-774). */
enum ExerciseUnit: string
{
    case Reps = 'reps';
    case Seconds = 'seconds';
    case Meters = 'meters';
}
