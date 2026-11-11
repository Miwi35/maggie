<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Repository\WorkoutMakeupRepository;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\WorkoutMakeupNotifications;
use Maggie\Health\Service\WorkoutStreak;
use Maggie\Health\Specification\CoversPlannedWorkout;
use Psr\Clock\ClockInterface;

/**
 * « Séance tenue » (MAG-771): the session done keeps the planned one and
 * notes the streak it reaches. A make-up still proposed for it is overtaken:
 * obsolete, its notification read and withdrawn. Checks the rule again, so a
 * replay keeps nothing twice.
 */
class KeepPlannedWorkout
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly WorkoutRepository $workouts,
        private readonly WorkoutMakeupRepository $makeups,
        private readonly WorkoutStreak $streak,
        private readonly WorkoutMakeupNotifications $notifications,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @return bool whether it kept it */
    public function execute(Workout $done, Workout $planned): bool
    {
        if (!(new CoversPlannedWorkout([]))->isSatisfiedBy($done, $planned) || null !== $this->workouts->findKeeperOf($planned)) {
            return false;
        }

        $done->setKeeps($planned)->setStreak($this->streak->keeping($planned));

        $proposal = $this->makeups->findOneByMissed($planned->getMakeupOf() ?? $planned);
        $overtaken = null !== $proposal && $proposal->isProposed() ? $proposal : null;
        $overtaken?->markObsolete();

        $this->em->flush();

        if (null !== $overtaken) {
            $this->notifications->withdraw($overtaken, $this->clock->now());
        }

        return true;
    }
}
