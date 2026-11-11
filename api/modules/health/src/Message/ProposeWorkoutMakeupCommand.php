<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class ProposeWorkoutMakeupCommand
{
    public function __construct(
        public string $plannedWorkoutId,
    ) {
    }
}
