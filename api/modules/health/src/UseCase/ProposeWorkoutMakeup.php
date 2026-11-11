<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Repository\WorkoutMakeupRepository;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\MakeupVariantsProviderInterface;
use Maggie\Health\Service\WorkoutMakeupNotifications;
use Maggie\Health\Service\WorkoutStreak;
use Maggie\Health\Specification\IsPlannedWorkoutMissed;
use Psr\Clock\ClockInterface;

/**
 * « Séance manquée » (MAG-771): one make-up proposal per missed session, with
 * the streak it keeps, and its notification. Checks the rule again, so a
 * second run of the reminders proposes nothing more; a make-up missed is not
 * made up in turn.
 */
class ProposeWorkoutMakeup
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly WorkoutRepository $workouts,
        private readonly WorkoutMakeupRepository $makeups,
        private readonly MakeupVariantsProviderInterface $variants,
        private readonly WorkoutStreak $streak,
        private readonly WorkoutMakeupNotifications $notifications,
        private readonly ClockInterface $clock,
    ) {
    }

    public function execute(Workout $planned): ?WorkoutMakeup
    {
        $now = $this->clock->now();
        $missed = new IsPlannedWorkoutMissed($now, null !== $this->workouts->findKeeperOf($planned));
        if (!$missed->isSatisfiedBy($planned) || null !== $this->makeups->findOneByMissed($planned)) {
            return null;
        }

        $variants = $this->variants->variantsFor($planned);
        $makeup = new WorkoutMakeup($planned->getUser(), $planned, $variants->softerMinutes, $variants->shorterMinutes, $this->streak->before($planned));
        $this->em->persist($makeup);
        $this->em->flush();

        $this->notifications->propose($makeup, $now);

        return $makeup;
    }
}
