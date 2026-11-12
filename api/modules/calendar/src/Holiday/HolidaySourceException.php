<?php

declare(strict_types=1);

namespace Maggie\Calendar\Holiday;

/** The French holidays API did not answer, or answered something that is not a year of holidays. */
final class HolidaySourceException extends \RuntimeException
{
}
