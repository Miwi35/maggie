<?php

namespace Maggie\Agenda\Message;

final readonly class DeleteEventCommand
{
    public function __construct(
        public string $eventId,
    ) {
    }
}
