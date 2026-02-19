<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\UpdateMealCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Meal, Meal> */
class UpdateMealProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Meal
    {
        $recipeIds = $data->getRecipes()->map(fn ($r) => (string) $r->getId())->toArray();

        $envelope = $this->bus->dispatch(new UpdateMealCommand(
            mealId: (string) $data->getId(),
            date: $data->getStartAt()->format('Y-m-d'),
            slot: $data->getSlot()->value,
            recipeIds: $recipeIds,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
