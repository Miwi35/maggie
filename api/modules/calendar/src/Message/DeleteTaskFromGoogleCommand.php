<?php

namespace Maggie\Calendar\Message;

final readonly class DeleteTaskFromGoogleCommand
{
    public function __construct(
        public string $taskId,
        public string $googleTaskId,
        public string $googleTaskListId,
        public string $userId,
    ) {
    }
}
