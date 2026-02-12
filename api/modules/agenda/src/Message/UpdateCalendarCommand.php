<?php

namespace Maggie\Agenda\Message;

final readonly class UpdateCalendarCommand
{
    public function __construct(
        public string $calendarId,
        public ?string $name = null,
        public ?string $description = null,
        public ?string $timeZone = null,
        public ?string $color = null,
        public ?bool $isDefault = null,
    ) {
    }
}
