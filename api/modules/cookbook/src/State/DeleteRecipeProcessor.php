<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\DeleteRecipeCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Recipe, void> */
class DeleteRecipeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteRecipeCommand(
            recipeId: (string) $data->getId(),
        ));
    }
}
