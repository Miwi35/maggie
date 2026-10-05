<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Meal, Meal> */
class CreateMealProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Meal
    {
        $user = $this->security->getUser();
        $recipeIds = $data->getRecipes()->map(fn ($r) => (string) $r->getId())->toArray();

        // Validation has already refused a meal without a day (Assert\NotNull
        // on Meal::$date); the throw is the type system's, not a second check.
        $date = $data->getDate() ?? throw new \LogicException('A validated meal always has a day.');

        $envelope = $this->bus->dispatch(new CreateMealCommand(
            date: $date->format('Y-m-d'),
            slot: $data->getSlot()->value,
            recipeIds: $recipeIds,
            agendaId: (string) $data->getAgenda()->getId(),
            userId: $user instanceof User ? (string) $user->getId() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
