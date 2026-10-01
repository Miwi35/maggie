<?php

namespace Maggie\Calendar\Message;

final readonly class CreateAgendaCommand
{
    public function __construct(
        public string $userId,
        public string $name,
        public ?string $description = null,
        public string $timeZone = 'Europe/Paris',
        public ?string $color = null,
        public bool $isDefault = false,
        /**
         * Set when the agenda is born from a Google calendar, so the link is
         * there before the agenda is published and indexed (MAG-148).
         */
        public ?string $googleCalendarId = null,
    ) {
    }
}
