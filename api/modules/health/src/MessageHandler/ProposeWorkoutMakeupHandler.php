<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Message\ProposeWorkoutMakeupCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\UseCase\ProposeWorkoutMakeup;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ProposeWorkoutMakeupHandler
{
    public function __construct(
        private readonly WorkoutRepository $workouts,
        private readonly ProposeWorkoutMakeup $proposeWorkoutMakeup,
    ) {
    }

    public function __invoke(ProposeWorkoutMakeupCommand $command): ?WorkoutMakeup
    {
        $planned = $this->workouts->find($command->plannedWorkoutId);

        return null === $planned ? null : $this->proposeWorkoutMakeup->execute($planned);
    }
}
