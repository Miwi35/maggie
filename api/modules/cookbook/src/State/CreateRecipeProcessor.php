<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Exception\UnknownUtensilException;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
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

        $ingredients = RecipeIngredientsPayload::fromContext($context);
        $utensils = RecipeUtensilsPayload::fromContext($context);

        try {
            $envelope = $this->bus->dispatch(new CreateRecipeCommand(
                userId: (string) $user->getId(),
                name: $data->getName(),
                servings: $data->getServings(),
                tags: $data->getTags(),
                notes: $data->getNotes(),
                ingredients: $ingredients,
                utensils: $utensils,
            ));
        } catch (HandlerFailedException $e) {
            // A utensil named by an id that is not one of the owner's: the request is wrong, not the server.
            if ($e->getPrevious() instanceof UnknownUtensilException) {
                throw new BadRequestHttpException($e->getPrevious()->getMessage(), $e);
            }

            throw $e;
        }

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
