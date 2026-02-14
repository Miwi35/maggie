<?php

namespace Maggie\Calendar\Message;

final readonly class PullTasksFromGoogleCommand
{
    public function __construct(
        public string $userId,
    ) {
    }
}
