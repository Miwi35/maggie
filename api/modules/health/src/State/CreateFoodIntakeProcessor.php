<?php

declare(strict_types=1);

namespace Maggie\Health\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Message\CreateFoodIntakeCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * `{"mealId": …}` alone is « Mangé »; anything else is a food typed by hand.
 *
 * @implements ProcessorInterface<FoodIntake, FoodIntake>
 */
class CreateFoodIntakeProcessor implements ProcessorInterface
{
    use DispatchesFoodIntakeCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): FoodIntake
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchFoodIntakeCommand($this->bus, new CreateFoodIntakeCommand(
            userId: (string) $user->getId(),
            mealId: $data->getMealId(),
            eatenAt: $data->getEatenAt()?->format(\DATE_ATOM),
            label: $data->getLabel(),
            ciqualCode: $data->getCiqualCode(),
            quantityGrams: $data->getQuantityGrams(),
            kcal: $data->getKcal(),
            proteinG: $data->getProteinG(),
            carbsG: $data->getCarbsG(),
            fatG: $data->getFatG(),
        ))->last(HandledStamp::class)->getResult();
    }
}
