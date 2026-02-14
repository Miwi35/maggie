<?php

namespace Maggie\Calendar\Message;

final readonly class DeleteEventFromGoogleCommand
{
    public function __construct(
        public string $agendaId,
        public string $googleEventId,
    ) {
    }
}
