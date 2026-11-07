<?php

declare(strict_types=1);

namespace Maggie\Health\Dto;

use Maggie\Health\Entity\DailyActivity;
use Symfony\Component\Validator\Constraints as Assert;

/** A day's steps and active energy of a Health Connect batch (MAG-708), as the JSON gave it: see {@see HealthSyncRequest}. */
final class SyncedDay
{
    public function __construct(
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('string'), new Assert\Date(message: 'Must be a local date, e.g. 2026-10-21.')])]
        public readonly mixed $date = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('integer'), new Assert\Range(min: 0, max: DailyActivity::MAX_STEPS)])]
        public readonly mixed $steps = null,
        #[Assert\Sequentially([new Assert\NotNull(), new Assert\Type('integer'), new Assert\Range(min: 0, max: DailyActivity::MAX_ACTIVE_KCAL)])]
        public readonly mixed $activeKcal = null,
    ) {
    }

    /** @param array<string, mixed> $item */
    public static function fromArray(array $item): self
    {
        return new self(
            date: $item['date'] ?? null,
            steps: $item['steps'] ?? null,
            activeKcal: $item['activeKcal'] ?? null,
        );
    }
}
