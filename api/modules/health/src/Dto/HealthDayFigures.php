<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

/**
 * A health journal day's computed columns (MAG-750), as read from the
 * detailed tables by {@see \Maggie\Health\Service\HealthDayCalculator}.
 */
final readonly class HealthDayFigures
{
    public function __construct(
        public ?float $weightKg = null,
        public ?float $bodyFatPercent = null,
        public ?\DateTimeImmutable $weighedAt = null,
        public ?int $steps = null,
        public ?int $eatenKcal = null,
        public ?int $expendedKcal = null,
        public ?int $sportMinutes = null,
        public bool $hasSource = false,
    ) {
    }
}
