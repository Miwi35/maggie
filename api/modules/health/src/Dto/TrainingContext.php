<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

/**
 * What the progression rules know besides the exercise's own history (MAG-776).
 * `difficulties` are the difficulty of the last sessions done, newest first;
 * `calibration` the best count per exercise of the last calibration session.
 */
final readonly class TrainingContext
{
    /**
     * @param list<array{id: string, difficulty: int}> $difficulties
     * @param array<string, int>|null                  $calibration
     */
    public function __construct(
        public \DateTimeImmutable $now,
        public ?int $programWeek = null,
        public array $difficulties = [],
        public ?array $calibration = null,
        public ?string $calibrationId = null,
    ) {
    }
}
