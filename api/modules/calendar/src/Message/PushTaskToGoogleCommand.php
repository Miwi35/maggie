<?php

namespace Maggie\Calendar\Message;

final readonly class PushTaskToGoogleCommand
{
    public function __construct(
        public string $taskId,
    ) {
    }
}
