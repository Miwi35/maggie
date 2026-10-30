<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Entity\Workout;
use Maggie\Health\Exception\DuplicateWorkoutException;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\WorkoutRules;
use Maggie\Health\UseCase\SaveWorkout;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the workout as it was.
 */
#[AsMessageHandler]
class UpdateWorkoutHandler
{
    public function __construct(
        private readonly SaveWorkout $saveWorkout,
        private readonly WorkoutRepository $workoutRepository,
        private readonly WorkoutRules $rules,
    ) {
    }

    public function __invoke(UpdateWorkoutCommand $command): Workout
    {
        $workout = $this->workoutRepository->findOneOfUser($command->userId, $command->workoutId)
            ?? throw new \DomainException("Workout not found: {$command->workoutId}");

        $type = null === $command->type ? $workout->getType() : $this->rules->type($command->type);
        $source = null === $command->source ? $workout->getSource() : $this->rules->source($command->source);
        $startedAt = null === $command->startedAt ? $workout->getStartedAt() : $this->rules->startedAt($command->startedAt);
        $feeling = $command->clearFeeling ? null : (null === $command->feeling ? $workout->getFeeling() : $this->rules->feeling($command->feeling));
        $title = $command->title ?? $workout->getTitle();
        $duration = $command->durationMinutes ?? $workout->getDurationMinutes();
        $distance = $command->clearDistance ? null : ($command->distanceMeters ?? $workout->getDistanceMeters());
        $kcal = $command->clearKcal ? null : ($command->kcal ?? $workout->getKcal());
        $heartRate = $command->clearAvgHeartRate ? null : ($command->avgHeartRate ?? $workout->getAvgHeartRate());
        $notes = $command->clearNotes ? null : ($command->notes ?? $workout->getNotes());
        $externalId = $command->clearExternalId ? null : ($command->externalId ?? $workout->getExternalId());
        $this->rules->assert($title, $duration, $distance, $kcal, $heartRate, $notes, $externalId);

        if (null !== $externalId && $externalId !== $workout->getExternalId()) {
            $other = $this->workoutRepository->findOneByExternalId($command->userId, $externalId);
            if (null !== $other && !$other->getId()->equals($workout->getId())) {
                throw new DuplicateWorkoutException();
            }
        }

        $workout
            ->setType($type)
            ->setTitle($title)
            ->setStartedAt($startedAt)
            ->setDurationMinutes($duration)
            ->setDistanceMeters($distance)
            ->setKcal($kcal)
            ->setAvgHeartRate($heartRate)
            ->setFeeling($feeling)
            ->setNotes($notes)
            ->setSource($source)
            ->setExternalId($externalId);

        return $this->saveWorkout->execute($workout);
    }
}
