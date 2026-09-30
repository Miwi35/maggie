<?php

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateEventCommand
{
    use ClearsFieldsTrait;

    /** @param list<'description'|'location'|'rrule'> $clearFields */
    public function __construct(
        public string $eventId,
        public ?string $summary = null,
        public ?\DateTimeImmutable $startAt = null,
        public ?\DateTimeImmutable $endAt = null,
        public ?string $description = null,
        public ?string $location = null,
        public ?bool $allDay = null,
        public ?string $rrule = null,
        public array $clearFields = [],
    ) {
    }
}
