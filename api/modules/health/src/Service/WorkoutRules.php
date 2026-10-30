<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Exception\InvalidWorkoutException;

/**
 * What a workout is held to whichever door it comes through (API, MCP).
 * Every refusal is an InvalidWorkoutException naming the field: a 400 for the
 * API, an error for the tool. Nothing is deduced.
 */
final class WorkoutRules
{
    public function type(string $value): WorkoutType
    {
        return WorkoutType::tryFrom($value)
            ?? throw new InvalidWorkoutException('type', "unknown value \"{$value}\". Use run, walk, bike, swim, strength, climbing or other.");
    }

    public function feeling(?string $value): ?WorkoutFeeling
    {
        if (null === $value) {
            return null;
        }

        return WorkoutFeeling::tryFrom($value)
            ?? throw new InvalidWorkoutException('feeling', "unknown value \"{$value}\". Use very_good, good, hard or very_hard.");
    }

    public function source(string $value): WorkoutSource
    {
        return WorkoutSource::tryFrom($value)
            ?? throw new InvalidWorkoutException('source', "unknown value \"{$value}\". Use manual, watch, activity, estimated or planned.");
    }

    /** ISO 8601 with a time and an offset, e.g. 2026-10-20T07:10:00+02:00: a date alone or a time without offset is refused, never completed. */
    public function startedAt(string $value): \DateTimeImmutable
    {
        $hint = 'must be ISO 8601 with time and offset, e.g. 2026-10-20T07:10:00+02:00.';
        if (1 !== preg_match('/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/', $value)) {
            throw new InvalidWorkoutException('startedAt', $hint);
        }

        try {
            return new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw new InvalidWorkoutException('startedAt', $hint);
        }
    }

    public function assert(string $title, int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $notes = null, ?string $externalId = null): void
    {
        if ('' === trim($title)) {
            throw new InvalidWorkoutException('title', 'must not be blank.');
        }
        if (mb_strlen($title) > 255) {
            throw new InvalidWorkoutException('title', 'must be at most 255 characters.');
        }
        if ($durationMinutes < 1 || $durationMinutes > Workout::MAX_DURATION_MINUTES) {
            throw new InvalidWorkoutException('durationMinutes', 'is in whole minutes, from 1 to 1440.');
        }
        if (null !== $distanceMeters && ($distanceMeters < 0 || $distanceMeters > Workout::MAX_DISTANCE_METERS)) {
            throw new InvalidWorkoutException('distanceMeters', 'is in whole metres, from 0 to 1000000: 5.4 km is 5400.');
        }
        if (null !== $kcal && ($kcal < 0 || $kcal > Workout::MAX_KCAL)) {
            throw new InvalidWorkoutException('kcal', 'is in whole kilocalories, from 0 to 20000.');
        }
        if (null !== $avgHeartRate && ($avgHeartRate < Workout::MIN_HEART_RATE || $avgHeartRate > Workout::MAX_HEART_RATE)) {
            throw new InvalidWorkoutException('avgHeartRate', 'is in beats per minute, from 20 to 250.');
        }
        if (null !== $notes && mb_strlen($notes) > 2000) {
            throw new InvalidWorkoutException('notes', 'must be at most 2000 characters.');
        }
        if (null !== $externalId && mb_strlen($externalId) > 255) {
            throw new InvalidWorkoutException('externalId', 'must be at most 255 characters.');
        }
    }
}
