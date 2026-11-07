<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Maggie\Health\Message\DeleteFoodIntakeCommand;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\UseCase\DeleteFoodIntake;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Deleting a meal's intake gives the meal back to the planned ones. */
#[AsMessageHandler]
class DeleteFoodIntakeHandler
{
    public function __construct(
        private readonly DeleteFoodIntake $deleteFoodIntake,
        private readonly FoodIntakeRepository $intakes,
    ) {
    }

    public function __invoke(DeleteFoodIntakeCommand $command): void
    {
        $intake = $this->intakes->findOneOfUser($command->userId, $command->foodIntakeId)
            ?? throw new \DomainException("Food intake not found: {$command->foodIntakeId}");

        $this->deleteFoodIntake->execute($intake);
    }
}
