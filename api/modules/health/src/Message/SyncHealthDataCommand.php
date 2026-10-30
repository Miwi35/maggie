<?php

declare(strict_types=1);

namespace Maggie\Health\Message;

/**
 * A Health Connect batch (MAG-708), already validated: upserted by
 * `externalId` for the workouts and the measurements, by `date` for the days.
 *
 * @phpstan-type SyncedWorkoutData array{externalId: string, type: string, title: ?string, startedAt: string, durationMinutes: int, distanceMeters: ?int, kcal: ?int, avgHeartRate: ?int, source: string}
 * @phpstan-type SyncedMeasurementData array{externalId: string, measuredAt: string, weightKg: ?float, bodyFatPercent: ?float, source: string}
 * @phpstan-type SyncedDayData array{date: string, steps: int, activeKcal: int}
 */
final readonly class SyncHealthDataCommand
{
    /**
     * @param list<SyncedWorkoutData>     $workouts
     * @param list<SyncedMeasurementData> $measurements
     * @param list<SyncedDayData>         $days
     */
    public function __construct(
        public string $userId,
        public array $workouts = [],
        public array $measurements = [],
        public array $days = [],
    ) {
    }
}
