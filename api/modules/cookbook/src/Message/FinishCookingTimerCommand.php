<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/**
 * The end of a timer, queued with a delay until its `endsAt` and handled by the
 * worker. `revision` is the schedule it was queued for: once the timer is
 * paused, moved or cancelled, it no longer matches and the message does nothing.
 */
final readonly class FinishCookingTimerCommand
{
    public function __construct(
        public string $timerId,
        public int $revision,
    ) {
    }
}
