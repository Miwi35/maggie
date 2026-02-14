<?php

namespace Maggie\Calendar\Dto;

use Symfony\Component\Validator\Constraints as Assert;

final readonly class ConnectGoogleCalendarInput
{
    public function __construct(
        #[Assert\NotBlank]
        public string $agendaId,
        #[Assert\NotBlank]
        public string $googleCalendarId,
    ) {
    }
}
