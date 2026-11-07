<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Message\UpdateFoodIntakeCommand;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent, and grams that are null are cleared. The
 * meal and the Ciqual food an intake came from do not change, and no
 * required figure is emptied: both are a 400.
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
        $previous = $context['previous_data'] ?? null;
        \assert($previous instanceof FoodIntake);
        // The patch is already applied to the managed entity: what it may not
        // change is checked against the intake as it was, before anything is sent.
        foreach (['mealId' => [$previous->getMealId(), $data->getMealId()], 'ciqualCode' => [$previous->getCiqualCode(), $data->getCiqualCode()]] as $field => [$before, $after]) {
            if ($before !== $after) {
                throw new BadRequestHttpException("{$field}: is set on creation only; delete the intake and record it again.");
            }
        }
        foreach (['eatenAt' => $data->getEatenAt(), 'kcal' => $data->getKcal(), 'proteinG' => $data->getProteinG(), 'carbsG' => $data->getCarbsG(), 'fatG' => $data->getFatG()] as $field => $value) {
            if (null === $value) {
                throw new BadRequestHttpException("{$field}: cannot be emptied.");
            }
        }

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
