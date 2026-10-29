<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** The same user already has a workout with this externalId: a 422 for the API, as the validator answers for a PATCH. */
final class DuplicateWorkoutException extends InvalidWorkoutException
{
    public function __construct()
    {
        parent::__construct('externalId', 'This externalId is already used by another workout.');
    }
}
