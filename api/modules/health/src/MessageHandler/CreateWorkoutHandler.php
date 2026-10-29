<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Exception\DuplicateWorkoutException;
use Maggie\Health\Message\CreateWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\WorkoutRules;
use Maggie\Health\UseCase\SaveWorkout;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateWorkoutHandler
{
    public function __construct(
        private readonly SaveWorkout $saveWorkout,
        private readonly UserRepository $userRepository,
        private readonly WorkoutRepository $workoutRepository,
        private readonly WorkoutRules $rules,
    ) {
    }

    public function __invoke(CreateWorkoutCommand $command): Workout
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $type = $this->rules->type($command->type);
        $feeling = $this->rules->feeling($command->feeling);
        $source = $this->rules->source($command->source);
        $startedAt = $this->rules->startedAt($command->startedAt);
        $this->rules->assert($command->title, $command->durationMinutes, $command->distanceMeters, $command->kcal, $command->avgHeartRate, $command->notes, $command->externalId);

        if (null !== $command->externalId && null !== $this->workoutRepository->findOneByExternalId($user, $command->externalId)) {
            throw new DuplicateWorkoutException();
        }

        $workout = (new Workout())
            ->setUser($user)
            ->setType($type)
            ->setTitle($command->title)
            ->setStartedAt($startedAt)
            ->setDurationMinutes($command->durationMinutes)
            ->setDistanceMeters($command->distanceMeters)
            ->setKcal($command->kcal)
            ->setAvgHeartRate($command->avgHeartRate)
            ->setFeeling($feeling)
            ->setNotes($command->notes)
            ->setSource($source)
            ->setExternalId($command->externalId);

        return $this->saveWorkout->execute($workout);
    }
}
