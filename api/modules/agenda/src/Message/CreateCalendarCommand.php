<?php

namespace Maggie\Agenda\Message;

final readonly class CreateCalendarCommand
{
    public function __construct(
        public string $name,
        public ?string $description = null,
        public string $timeZone = 'Europe/Paris',
        public ?string $color = null,
        public bool $isDefault = false,
    ) {
    }
}
