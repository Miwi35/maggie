<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Message\KeepPlannedWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\UseCase\KeepPlannedWorkout;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class KeepPlannedWorkoutHandler
{
    public function __construct(
        private readonly WorkoutRepository $workouts,
        private readonly KeepPlannedWorkout $keepPlannedWorkout,
    ) {
    }

    /** Gone since, or another user's planned session: nothing is kept. */
    public function __invoke(KeepPlannedWorkoutCommand $command): bool
    {
        $done = $this->workouts->find($command->doneWorkoutId);
        $planned = null === $done ? null : $this->workouts->findOneOfUser($done->getUser(), $command->plannedWorkoutId);
        if (null === $done || null === $planned) {
            return false;
        }

        return $this->keepPlannedWorkout->execute($done, $planned);
    }
}
