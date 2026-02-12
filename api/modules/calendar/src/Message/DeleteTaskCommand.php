<?php

namespace Maggie\Calendar\Message;

final readonly class DeleteTaskCommand
{
    public function __construct(
        public string $taskId,
    ) {
    }
}
