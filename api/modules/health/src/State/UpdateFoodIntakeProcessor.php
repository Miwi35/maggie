<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Message\UpdateFoodIntakeCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and grams that are null are cleared. The
 * meal and the Ciqual food an intake came from do not change.
 *
 * @implements ProcessorInterface<FoodIntake, FoodIntake>
 */
class UpdateFoodIntakeProcessor implements ProcessorInterface
{
    use DispatchesFoodIntakeCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): FoodIntake
    {
        return $this->dispatchFoodIntakeCommand($this->bus, new UpdateFoodIntakeCommand(
            userId: (string) $data->getUser()->getId(),
            foodIntakeId: (string) $data->getId(),
            eatenAt: $data->getEatenAt()?->format(\DATE_ATOM),
            label: $data->getLabel() ?? '',
            quantityGrams: $data->getQuantityGrams(),
            kcal: $data->getKcal(),
            proteinG: $data->getProteinG(),
            carbsG: $data->getCarbsG(),
            fatG: $data->getFatG(),
            clearQuantity: null === $data->getQuantityGrams(),
        ))->last(HandledStamp::class)->getResult();
    }
}
