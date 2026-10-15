<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Message\UpdateMealSettingsCommand;
use Maggie\Cookbook\Repository\MealSettingsRepository;
use Maggie\Cookbook\UseCase\UpdateMealSettings;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateMealSettingsHandler
{
    public function __construct(
        private readonly UpdateMealSettings $updateMealSettings,
        private readonly MealSettingsRepository $repository,
    ) {
    }

    public function __invoke(UpdateMealSettingsCommand $command): MealSettings
    {
        $settings = $this->repository->findOneBy(['id' => $command->mealSettingsId, 'user' => $command->userId])
            ?? throw new \DomainException("Meal settings not found: {$command->mealSettingsId}");

        if (!\is_int($command->defaultGuests) || $command->defaultGuests < 1) {
            throw new \DomainException('defaultGuests must be a whole number of at least 1.');
        }

        $previous = $settings->getDefaultGuests();
        $settings->setDefaultGuests($command->defaultGuests);

        return $this->updateMealSettings->execute($settings, $previous);
    }
}
