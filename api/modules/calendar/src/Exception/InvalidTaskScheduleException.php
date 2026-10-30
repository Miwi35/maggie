<?php

declare(strict_types=1);

namespace Maggie\Calendar\Exception;

/** The programmation a task was given does not hold together: a time or reminders without `time`, a moment without a day (MAG-718). */
final class InvalidTaskScheduleException extends \DomainException
{
}
