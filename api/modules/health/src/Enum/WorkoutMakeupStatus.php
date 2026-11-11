<?php

declare(strict_types=1);

namespace Maggie\Health\Enum;

/**
 * Where a make-up proposal stands (MAG-771): offered, answered, or overtaken
 * by a session done the same day, which kept the missed one after all.
 */
enum WorkoutMakeupStatus: string
{
    case Proposed = 'proposed';
    case Chosen = 'chosen';
    case Obsolete = 'obsolete';
}
