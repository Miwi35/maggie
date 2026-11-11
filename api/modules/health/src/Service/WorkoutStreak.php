<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Specification\IsPlannedWorkoutMissed;
use Psr\Clock\ClockInterface;

/**
 * The streak of planned sessions kept in a row (MAG-771). A make-up counts
 * for the session it replaces, never on its own. A planned session neither
 * kept nor made up breaks it once it is missed ({@see IsPlannedWorkoutMissed});
 * one not over yet, or whose make-up is still to come, breaks nothing.
 */
final class WorkoutStreak
{
    public function __construct(
        private readonly WorkoutRepository $workouts,
        private readonly ClockInterface $clock,
    ) {
    }

    /** The streak just before this planned session: what keeping it adds one to, what missing it puts at stake. */
    public function before(Workout $planned): int
    {
        $now = $this->clock->now();
        $user = $planned->getUser();
        $kept = $this->workouts->findKeptIds($user);
        $makeups = [];
        foreach ($this->workouts->findMakeups($user) as $makeup) {
            $makeups[(string) $makeup->getMakeupOf()?->getId()][] = $makeup;
        }

        $streak = 0;
        foreach ($this->workouts->findPlannedOriginalsBefore($user, $planned->getStartedAt()) as $original) {
            $replacements = $makeups[(string) $original->getId()] ?? [];
            if (isset($kept[(string) $original->getId()]) || [] !== array_filter($replacements, static fn (Workout $m): bool => isset($kept[(string) $m->getId()]))) {
                ++$streak;

                continue;
            }
            $pending = !IsPlannedWorkoutMissed::isOver($original, $now)
                || [] !== array_filter($replacements, static fn (Workout $m): bool => !IsPlannedWorkoutMissed::isOver($m, $now));
            if (!$pending) {
                break;
            }
        }

        return $streak;
    }

    /**
     * The streak once this planned session is kept: a make-up keeps the one it
     * replaces.
     */
    public function keeping(Workout $planned): int
    {
        return $this->before($planned->getMakeupOf() ?? $planned) + 1;
    }
}
