<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Message\CreateWorkoutCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Workout, Workout> */
class CreateWorkoutProcessor implements ProcessorInterface
{
    use DispatchesWorkoutCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Workout
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchWorkoutCommand($this->bus, new CreateWorkoutCommand(
            userId: (string) $user->getId(),
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
        ))->last(HandledStamp::class)->getResult();
    }
}
