<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and an optional one that is null is cleared.
 *
 * @implements ProcessorInterface<Workout, Workout>
 */
class UpdateWorkoutProcessor implements ProcessorInterface
{
    use DispatchesWorkoutCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Workout
    {
        return $this->dispatchWorkoutCommand($this->bus, new UpdateWorkoutCommand(
            userId: (string) $data->getUser()->getId(),
            workoutId: (string) $data->getId(),
            type: $data->getType()->value,
            title: $data->getTitle(),
            startedAt: $data->getStartedAt()->format(\DATE_ATOM),
            durationMinutes: $data->getDurationMinutes(),
            distanceMeters: $data->getDistanceMeters(),
            kcal: $data->getKcal(),
            avgHeartRate: $data->getAvgHeartRate(),
            feeling: $data->getFeeling()?->value,
            notes: $data->getNotes(),
            source: $data->getSource()->value,
            externalId: $data->getExternalId(),
            clearDistance: null === $data->getDistanceMeters(),
            clearKcal: null === $data->getKcal(),
            clearAvgHeartRate: null === $data->getAvgHeartRate(),
            clearFeeling: null === $data->getFeeling(),
            clearNotes: null === $data->getNotes(),
            clearExternalId: null === $data->getExternalId(),
        ))->last(HandledStamp::class)->getResult();
    }
}
