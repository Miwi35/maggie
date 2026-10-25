<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

use Maggie\Core\Enum\Activity;

/** Ends the user's activity in progress, or starts it over (MAG-613). */
final readonly class DeleteActivityStatusCommand
{
    public function __construct(
        public string $userId,
        public Activity $activity,
    ) {
    }
}
