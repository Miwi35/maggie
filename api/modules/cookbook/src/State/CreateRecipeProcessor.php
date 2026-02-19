<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Recipe, Recipe> */
class CreateRecipeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Recipe
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateRecipeCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            servings: $data->getServings(),
            tags: $data->getTags(),
            notes: $data->getNotes(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
