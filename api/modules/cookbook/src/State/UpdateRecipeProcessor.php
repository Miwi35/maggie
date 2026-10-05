<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Recipe, Recipe> */
class UpdateRecipeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Recipe
    {
        $ingredients = RecipeIngredientsPayload::fromContext($context);

        $envelope = $this->bus->dispatch(new UpdateRecipeCommand(
            recipeId: (string) $data->getId(),
            name: $data->getName(),
            servings: $data->getServings(),
            tags: $data->getTags(),
            notes: $data->getNotes(),
            ingredients: $ingredients,
            clearFields: null === $data->getNotes() ? ['notes'] : [],
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
