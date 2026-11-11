<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

final readonly class CreateWorkoutCommand
{
    /**
     * @param string           $startedAt ISO 8601 with time and offset
     * @param list<mixed>|null $exercises the list as sent, checked by the handler (MAG-775)
     */
    public function __construct(
        public string $userId,
        public string $type,
        public string $title,
        public string $startedAt,
        public int $durationMinutes,
        public ?int $distanceMeters = null,
        public ?int $kcal = null,
        public ?int $avgHeartRate = null,
        public ?string $feeling = null,
        public ?string $notes = null,
        public string $source = 'manual',
        public ?string $externalId = null,
        public ?array $exercises = null,
        public ?int $difficulty = null,
        public ?string $variant = null,
    ) {
    }
}
