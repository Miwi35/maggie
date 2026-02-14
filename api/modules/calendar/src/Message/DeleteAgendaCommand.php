<?php

namespace Maggie\Calendar\Message;

final readonly class DeleteAgendaCommand
{
    public function __construct(
        public string $agendaId,
        public bool $deleteGoogleCalendar = false,
    ) {
    }
}
