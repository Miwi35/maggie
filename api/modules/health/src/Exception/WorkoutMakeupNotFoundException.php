<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** No such make-up proposal, or another user's: the two answer alike. */
final class WorkoutMakeupNotFoundException extends \DomainException
{
    public function __construct(string $makeupId)
    {
        parent::__construct(\sprintf('Workout makeup not found: %s', $makeupId));
    }
}
