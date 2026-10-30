<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutType;
use Symfony\Component\Validator\Constraints as Assert;

/** A workout of a Health Connect batch (MAG-708), as the JSON gave it: see {@see HealthSyncRequest}. */
final class SyncedWorkout
{
    public const SOURCES = ['watch'];

    public function __construct(
        #[Assert\Sequentially([new Assert\NotBlank(), new Assert\Type('string'), new Assert\Length(max: 255)])]
        public readonly mixed $externalId = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Choice(callback: [self::class, 'types'])])]
        public readonly mixed $type = null,
        #[Assert\Sequentially([new Assert\Type('string'), new Assert\NotBlank(normalizer: 'trim', allowNull: true), new Assert\Length(max: 255)])]
        public readonly mixed $title = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('string'), new Assert\Regex(pattern: SyncedMeasurement::INSTANT, message: 'Must be ISO 8601 with time and offset, e.g. 2026-10-20T07:10:00+02:00.')])]
        public readonly mixed $startedAt = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('integer'), new Assert\Range(min: 1, max: Workout::MAX_DURATION_MINUTES)])]
        public readonly mixed $durationMinutes = null,
        #[Assert\Sequentially([new Assert\Type('integer'), new Assert\Range(min: 0, max: Workout::MAX_DISTANCE_METERS)])]
        public readonly mixed $distanceMeters = null,
        #[Assert\Sequentially([new Assert\Type('integer'), new Assert\Range(min: 0, max: Workout::MAX_KCAL)])]
        public readonly mixed $kcal = null,
        #[Assert\Sequentially([new Assert\Type('integer'), new Assert\Range(min: Workout::MIN_HEART_RATE, max: Workout::MAX_HEART_RATE)])]
        public readonly mixed $avgHeartRate = null,
        #[Assert\Choice(choices: self::SOURCES)]
        public readonly mixed $source = 'watch',
    ) {
    }

    /** @param array<string, mixed> $item */
    public static function fromArray(array $item): self
    {
        return new self(
            externalId: $item['externalId'] ?? null,
            type: $item['type'] ?? null,
            title: $item['title'] ?? null,
            startedAt: $item['startedAt'] ?? null,
            durationMinutes: $item['durationMinutes'] ?? null,
            distanceMeters: $item['distanceMeters'] ?? null,
            kcal: $item['kcal'] ?? null,
            avgHeartRate: $item['avgHeartRate'] ?? null,
            source: $item['source'] ?? 'watch',
        );
    }

    /** @return list<string> */
    public static function types(): array
    {
        return array_map(static fn (WorkoutType $type): string => $type->value, WorkoutType::cases());
    }
}
