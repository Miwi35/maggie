<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\UpdateTaskSettingsCommand;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Calendar\UseCase\UpdateTaskSettings;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTaskSettingsHandler
{
    public function __construct(
        private readonly UpdateTaskSettings $updateTaskSettings,
        private readonly TaskSettingsRepository $repository,
    ) {
    }

    public function __invoke(UpdateTaskSettingsCommand $command): TaskSettings
    {
        $settings = $this->repository->findOneBy(['id' => $command->taskSettingsId, 'user' => $command->userId])
            ?? throw new \DomainException("Task settings not found: {$command->taskSettingsId}");

        if (null !== $command->orderWeights) {
            try {
                $settings->setOrderWeights(TaskOrderWeights::merge($settings->getOrderWeights(), $command->orderWeights));
            } catch (\InvalidArgumentException $e) {
                throw new \DomainException($e->getMessage(), 0, $e);
            }
        }

        return $this->updateTaskSettings->execute($settings);
    }
}
