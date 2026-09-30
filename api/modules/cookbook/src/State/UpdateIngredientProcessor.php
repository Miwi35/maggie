<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\UpdateIngredientCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Ingredient, Ingredient> */
class UpdateIngredientProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Ingredient
    {
        /** @var Ingredient|null $previous */
        $previous = $context['previous_data'] ?? null;

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if ($previous !== null) {
            foreach ([
                'defaultUnit' => 'getDefaultUnit',
                'ciqualAlimCode' => 'getCiqualAlimCode',
                'kcalPer100g' => 'getKcalPer100g',
                'proteinPer100g' => 'getProteinPer100g',
                'carbsPer100g' => 'getCarbsPer100g',
                'fatPer100g' => 'getFatPer100g',
            ] as $field => $getter) {
                if ($data->$getter() === null && $previous->$getter() !== null) {
                    $clearFields[] = $field;
                }
            }
        }

        $envelope = $this->bus->dispatch(new UpdateIngredientCommand(
            ingredientId: (string) $data->getId(),
            name: $data->getName(),
            category: $data->getCategory()->value,
            defaultUnit: $data->getDefaultUnit()?->value,
            ciqualAlimCode: $data->getCiqualAlimCode(),
            kcalPer100g: $data->getKcalPer100g(),
            proteinPer100g: $data->getProteinPer100g(),
            carbsPer100g: $data->getCarbsPer100g(),
            fatPer100g: $data->getFatPer100g(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
