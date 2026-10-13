<?php

namespace Maggie\Calendar\Message;

final readonly class PushAgendaNameToGoogleCommand
{
    public function __construct(
        public string $agendaId,
        public string $previousName,
    ) {
    }
}
