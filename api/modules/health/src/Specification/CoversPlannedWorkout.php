<?php

declare(strict_types=1);

namespace Maggie\Health\Specification;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\WorkoutSource;

/**
 * « Séance tenue » (MAG-771): a session done keeps a planned one of the same
 * local day — the day read in the offset of the session done's `startedAt` —
 * whatever the hours. Only a session that becomes done (created done, turned
 * from planned to done) or moves (`startedAt`) is a transition: a re-save of
 * its notes is not. One planned session at most per session done.
 *
 * The planned sessions offered are the ones not kept yet, the earliest first
 * ({@see \Maggie\Health\Repository\WorkoutRepository::findKeepablePlanned()}):
 * {@see self::pick()} takes the first one satisfied. The listener asks
 * {@see self::isTransition()} first; the use case, after the flush, only
 * {@see self::isSatisfiedBy()}.
 */
final readonly class CoversPlannedWorkout
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet */
    public function __construct(
        private array $changeSet,
    ) {
    }

    /** Whether this change of the session may make it keep a planned one. */
    public function isTransition(Workout $done): bool
    {
        if ($done->isPlanned() || null !== $done->getKeeps()) {
            return false;
        }
        if (isset($this->changeSet['startedAt'])) {
            return true;
        }
        if (!isset($this->changeSet['source'])) {
            return false;
        }
        $before = $this->changeSet['source'][0];

        return null === $before || WorkoutSource::Planned === $before || WorkoutSource::Planned->value === $before;
    }

    public function isSatisfiedBy(Workout $done, Workout $planned): bool
    {
        if ($done->isPlanned() || null !== $done->getKeeps() || !$planned->isPlanned() || $planned === $done) {
            return false;
        }
        if ((string) $planned->getUser()->getId() !== (string) $done->getUser()->getId()) {
            return false;
        }

        return $planned->getStartedAt()->setTimezone($done->getStartedAt()->getTimezone())->format('Y-m-d')
            === $done->getStartedAt()->format('Y-m-d');
    }

    /**
     * The planned session this one keeps: the first satisfied of the candidates,
     * which come the earliest first.
     *
     * @param iterable<Workout> $candidates
     */
    public function pick(Workout $done, iterable $candidates): ?Workout
    {
        foreach ($candidates as $planned) {
            if ($this->isSatisfiedBy($done, $planned)) {
                return $planned;
            }
        }

        return null;
    }

    /**
     * The local day of the session done, as the instants that bound it.
     *
     * @return array{\DateTimeImmutable, \DateTimeImmutable}
     */
    public static function dayOf(Workout $done): array
    {
        $start = $done->getStartedAt()->setTime(0, 0);

        return [$start, $start->modify('+1 day')];
    }
}
