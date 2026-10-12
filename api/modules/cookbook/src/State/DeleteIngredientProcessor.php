<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Exception\IngredientInUseException;
use Maggie\Cookbook\Message\DeleteIngredientCommand;
use Symfony\Component\HttpKernel\Exception\ConflictHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Ingredient, void> */
class DeleteIngredientProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        try {
            $this->bus->dispatch(new DeleteIngredientCommand(
                ingredientId: (string) $data->getId(),
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof IngredientInUseException) {
                throw new ConflictHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
