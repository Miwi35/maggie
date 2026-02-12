<?php

namespace Maggie\Agenda\Message;

final readonly class DeleteCalendarCommand
{
    public function __construct(
        public string $calendarId,
    ) {
    }
}
