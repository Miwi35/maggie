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
    ) {
    }
}
