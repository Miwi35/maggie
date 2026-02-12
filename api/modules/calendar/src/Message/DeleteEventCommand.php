<?php

namespace Maggie\Calendar\Message;

final readonly class DeleteEventCommand
{
    public function __construct(
        public string $eventId,
    ) {
    }
}
