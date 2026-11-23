<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

use Maggie\Calendar\Enum\ProactionKind;

final readonly class ScheduleProactionCommand
{
    public function __construct(
        public string $userId,
        public string $prompt,
        public \DateTimeImmutable $scheduledAt,
        public ?string $summary = null,
        public ProactionKind $kind = ProactionKind::Reminder,
        public ?string $rrule = null,
    ) {
    }
}
