<?php

namespace Maggie\Calendar\Message;

final readonly class PullFromGoogleCommand
{
    public function __construct(
        public string $agendaId,
    ) {
    }
}
