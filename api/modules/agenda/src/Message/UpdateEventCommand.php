<?php

namespace Maggie\Agenda\Message;

final readonly class UpdateEventCommand
{
    public function __construct(
        public string $eventId,
        public ?string $summary = null,
        public ?\DateTimeImmutable $startAt = null,
        public ?\DateTimeImmutable $endAt = null,
        public ?string $description = null,
        public ?string $location = null,
    ) {
    }
}
