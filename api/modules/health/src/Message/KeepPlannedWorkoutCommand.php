<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class KeepPlannedWorkoutCommand
{
    public function __construct(
        public string $doneWorkoutId,
        public string $plannedWorkoutId,
    ) {
    }
}
