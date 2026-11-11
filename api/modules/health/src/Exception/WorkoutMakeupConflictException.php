<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** The proposal was already answered, or overtaken by a session done: 409. */
final class WorkoutMakeupConflictException extends \DomainException
{
}
