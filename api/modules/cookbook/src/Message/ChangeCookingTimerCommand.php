<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

use Maggie\Cookbook\Enum\CookingTimerAction;

/** Pause, resume, ±1 min, cancel or « OK » on a timer of the user (MAG-582). */
final readonly class ChangeCookingTimerCommand
{
    public function __construct(
        public string $userId,
        public string $timerId,
        public CookingTimerAction $action,
        public ?int $seconds = null,
    ) {
    }
}
