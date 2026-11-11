<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Exception\WorkoutMakeupConflictException;
use Maggie\Health\Service\MakeupSchedule;
use Maggie\Health\Service\WorkoutMakeupNotifications;
use Psr\Clock\ClockInterface;

/**
 * The answer to « Séance manquée » (MAG-771): a planned make-up, same type
 * and title — tonight at the next quarter hour for softer and shorter,
 * tomorrow at the same hour — then the notification is read and withdrawn.
 * A proposal answers once.
 */
class ChooseWorkoutMakeup
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MakeupSchedule $schedule,
        private readonly WorkoutMakeupNotifications $notifications,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @throws WorkoutMakeupConflictException already chosen, or obsolete */
    public function execute(WorkoutMakeup $makeup, MakeupOption $option): WorkoutMakeup
    {
        if (!$makeup->isProposed()) {
            throw new WorkoutMakeupConflictException(\sprintf('This make-up is already %s.', $makeup->getStatus()->value));
        }

        $now = $this->clock->now();
        $missed = $makeup->getMissed();
        $workout = (new Workout())
            ->setUser($makeup->getUser())
            ->setType($missed->getType())
            ->setTitle($missed->getTitle())
            ->setStartedAt($this->schedule->start($makeup, $option, $now))
            ->setDurationMinutes($makeup->minutesFor($option))
            ->setSource(WorkoutSource::Planned)
            ->setMakeupOf($missed)
            ->setMakeupOption($option);
        $this->em->persist($workout);
        $makeup->choose($option, $workout);
        $this->em->flush();

        $this->notifications->withdraw($makeup, $now);

        return $makeup;
    }
}
