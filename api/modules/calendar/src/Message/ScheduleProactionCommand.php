<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

final readonly class ScheduleProactionCommand
{
    public function __construct(
        public string $userId,
        public string $prompt,
        public \DateTimeImmutable $scheduledAt,
        public ?string $summary = null,
    ) {
    }
}
