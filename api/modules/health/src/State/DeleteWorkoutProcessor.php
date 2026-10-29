<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Workout, void> */
class DeleteWorkoutProcessor implements ProcessorInterface
{
    use DispatchesWorkoutCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->dispatchWorkoutCommand($this->bus, new DeleteWorkoutCommand(
            userId: (string) $data->getUser()->getId(),
            workoutId: (string) $data->getId(),
        ));
    }
}
