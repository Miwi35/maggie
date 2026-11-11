<?php

declare(strict_types=1);

namespace Maggie\Health\Specification;

use Maggie\Health\Entity\Workout;

/**
 * A planned session whose place in the agenda may have moved (MAG-777): created,
 * or its title, start, duration or source changed. Its event mirrors exactly these.
 */
final readonly class IsPlannedWorkout
{
    /** What the agenda's event shows of the session. */
    private const FIELDS = ['title', 'startedAt', 'durationMinutes', 'source'];

    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Workout $workout): bool
    {
        return $workout->isPlanned() && [] !== array_intersect(self::FIELDS, array_keys($this->changeSet));
    }
}
