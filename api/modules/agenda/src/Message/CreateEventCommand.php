<?php

namespace Maggie\Agenda\Message;

final readonly class CreateEventCommand
{
    public function __construct(
        public string $summary,
        public \DateTimeImmutable $startAt,
        public \DateTimeImmutable $endAt,
        public ?string $calendarId = null,
        public ?string $description = null,
        public ?string $location = null,
        public string $timeZone = 'Europe/Paris',
        public bool $allDay = false,
        public ?string $rrule = null,
        public ?string $recurringEventId = null,
        public ?\DateTimeImmutable $originalStartAt = null,
        public ?string $status = null,
    ) {
    }
}
