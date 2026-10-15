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
        $date = $data->getDate() ?? throw new \LogicException('A validated meal always has a day.');

        $envelope = $this->bus->dispatch(new UpdateMealCommand(
            mealId: (string) $data->getId(),
            date: $date->format('Y-m-d'),
            slot: $data->getSlot()->value,
            recipeIds: $recipeIds,
            // PATCH has already written the body into the meal: null is the
            // client asking for the default back, or a body that left it out.
            guests: $data->getOwnGuests(),
            useDefaultGuests: null === $data->getOwnGuests(),
            customTitle: $data->getCustomTitle() ?? '',
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
