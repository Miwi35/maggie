<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** What the user thinks of a spend, looking back at it a month later. */
enum RetrospectVerdict: string
{
    /** Not looked at yet — a state of the review, not a missing value. */
    case Unrated = 'unrated';
    case Keep = 'keep';
    case Avoidable = 'avoidable';
}
