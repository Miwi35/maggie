<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Message\DeleteWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\UseCase\DeleteWorkout;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteWorkoutHandler
{
    public function __construct(
        private readonly DeleteWorkout $deleteWorkout,
        private readonly WorkoutRepository $workoutRepository,
    ) {
    }

    public function __invoke(DeleteWorkoutCommand $command): void
    {
        $workout = $this->workoutRepository->findOneOfUser($command->userId, $command->workoutId)
            ?? throw new \DomainException("Workout not found: {$command->workoutId}");

        $this->deleteWorkout->execute($workout);
    }
}
