<?php

declare(strict_types=1);

namespace Maggie\Calendar\Exception;

/** The parent a task was given cannot be one: unknown, itself, or a subtask already (MAG-404). */
final class InvalidTaskParentException extends \DomainException
{
}
