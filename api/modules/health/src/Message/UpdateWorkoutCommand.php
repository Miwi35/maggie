<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * Null leaves a field untouched; the `clear*` flags empty an optional one
 * (a merge-patch `{"kcal": null}`). `exercises`, when given, replaces the
 * whole list (MAG-775).
 */
final readonly class UpdateWorkoutCommand
{
    /** @param list<mixed>|null $exercises */
    public function __construct(
        public string $userId,
        public string $workoutId,
        public ?string $type = null,
        public ?string $title = null,
        public ?string $startedAt = null,
        public ?int $durationMinutes = null,
        public ?int $distanceMeters = null,
        public ?int $kcal = null,
        public ?int $avgHeartRate = null,
        public ?string $feeling = null,
        public ?string $notes = null,
        public ?string $source = null,
        public ?string $externalId = null,
        public ?array $exercises = null,
        public ?int $difficulty = null,
        public ?string $variant = null,
        public bool $clearDistance = false,
        public bool $clearKcal = false,
        public bool $clearAvgHeartRate = false,
        public bool $clearFeeling = false,
        public bool $clearNotes = false,
        public bool $clearExternalId = false,
        public bool $clearDifficulty = false,
        public bool $clearVariant = false,
    ) {
    }
}
