<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** Lays down the reward of a task just done (MAG-508); a task already rewarded keeps its own. */
final readonly class GrantTaskRewardCommand
{
    public function __construct(
        public string $userId,
        public string $taskId,
    ) {
    }
}
