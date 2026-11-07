<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Message\DeleteFoodIntakeCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<FoodIntake, void> */
class DeleteFoodIntakeProcessor implements ProcessorInterface
{
    use DispatchesFoodIntakeCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->dispatchFoodIntakeCommand($this->bus, new DeleteFoodIntakeCommand(
            userId: (string) $data->getUser()->getId(),
            foodIntakeId: (string) $data->getId(),
        ));
    }
}
