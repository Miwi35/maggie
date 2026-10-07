<?php

namespace Maggie\Calendar\Message;

final readonly class CreateEventCommand
{
    /** @param array<string, mixed>|null $reminders Google's shape: {useDefault, overrides: [{method, minutes}]} */
    public function __construct(
        public string $summary,
        public \DateTimeImmutable $startAt,
        public \DateTimeImmutable $endAt,
        public ?string $agendaId = null,
        public ?string $description = null,
        public ?string $location = null,
        public string $timeZone = 'Europe/Paris',
        public bool $allDay = false,
        public ?string $rrule = null,
        public ?string $recurringEventId = null,
        public ?\DateTimeImmutable $originalStartAt = null,
        public ?string $status = null,
        public ?array $reminders = null,
        public ?string $userId = null,
    ) {
    }
}
