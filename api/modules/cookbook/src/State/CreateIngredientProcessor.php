<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateIngredientCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Ingredient, Ingredient> */
class CreateIngredientProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Ingredient
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateIngredientCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            category: $data->getCategory()->value,
            defaultUnit: $data->getDefaultUnit()?->value,
            ciqualAlimCode: $data->getCiqualAlimCode(),
            kcalPer100g: $data->getKcalPer100g(),
            proteinPer100g: $data->getProteinPer100g(),
            carbsPer100g: $data->getCarbsPer100g(),
            fatPer100g: $data->getFatPer100g(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
