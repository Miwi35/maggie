<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

use Maggie\Calendar\Entity\Event;

final readonly class CreatePeriodCommand
{
    /**
     * A timed period carries `startAt` and `endAt`; an all-day one `startDate` and
     * `endDate`, the end excluded, one day when absent — as an event (MAG-382).
     */
    public function __construct(
        public string $userId,
        public string $agendaId,
        public string $name,
        public bool $allDay = false,
        public ?\DateTimeImmutable $startAt = null,
        public ?\DateTimeImmutable $endAt = null,
        public ?\DateTimeImmutable $startDate = null,
        public ?\DateTimeImmutable $endDate = null,
        public string $timeZone = Event::FALLBACK_TIME_ZONE,
        public ?string $rrule = null,
        public ?string $holiday = null,
    ) {
    }
}
