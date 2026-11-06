<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

/** What {@see TripRecheck} found: nothing to do, a trip kept again, or a choice only Maggie can make. */
enum TripRecheckOutcome: string
{
    case Unchanged = 'unchanged';
    case Updated = 'updated';
    case NeedsChoice = 'needsChoice';
}
