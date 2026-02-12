<?php

namespace Maggie\Calendar\Message;

final readonly class UpdateAgendaCommand
{
    public function __construct(
        public string $agendaId,
        public ?string $name = null,
        public ?string $description = null,
        public ?string $timeZone = null,
        public ?string $color = null,
        public ?bool $isDefault = null,
    ) {
    }
}
