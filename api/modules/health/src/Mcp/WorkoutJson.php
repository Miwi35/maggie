<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp;

use Maggie\Health\Entity\Workout;

/** What the MCP tool says about a workout: the API's shape plus the French labels. */
final class WorkoutJson
{
    /** @return array<string, mixed> */
    public static function of(Workout $workout): array
    {
        return [
            'id' => (string) $workout->getId(),
            'type' => $workout->getType()->value,
            'typeLabel' => $workout->getType()->label(),
            'title' => $workout->getTitle(),
            'startedAt' => $workout->getStartedAt()->format(\DATE_ATOM),
            'durationMinutes' => $workout->getDurationMinutes(),
            'distanceMeters' => $workout->getDistanceMeters(),
            'kcal' => $workout->getKcal(),
            'avgHeartRate' => $workout->getAvgHeartRate(),
            'feeling' => $workout->getFeeling()?->value,
            'feelingLabel' => $workout->getFeeling()?->label(),
            'notes' => $workout->getNotes(),
            'source' => $workout->getSource()->value,
            'sourceLabel' => $workout->getSource()->label(),
            'externalId' => $workout->getExternalId(),
        ];
    }

    /**
     * @param list<Workout> $workouts
     *
     * @return list<array<string, mixed>>
     */
    public static function list(array $workouts): array
    {
        return array_map(self::of(...), $workouts);
    }
}
