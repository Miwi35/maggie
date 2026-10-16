<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\UpdateTaskSettingsCommand;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskAlertSchedule;
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

        // Everything is checked before anything is set: a refused field changes none.
        try {
            $orderWeights = null === $command->orderWeights ? null : TaskOrderWeights::merge($settings->getOrderWeights(), $command->orderWeights);
            $alertSchedule = null === $command->alertSchedule ? null : TaskAlertSchedule::merge($settings->getAlertSchedule(), $command->alertSchedule);
            if (null !== $command->morningTime && (!\is_string($command->morningTime) || 1 !== preg_match(TaskSettings::MORNING_TIME_PATTERN, $command->morningTime))) {
                throw new \InvalidArgumentException('morningTime must be a time "HH:MM" from 00:00 to 23:59.');
            }
            if (null !== $command->eveningTime && (!\is_string($command->eveningTime) || 1 !== preg_match(TaskSettings::MORNING_TIME_PATTERN, $command->eveningTime))) {
                throw new \InvalidArgumentException('eveningTime must be a time "HH:MM" from 00:00 to 23:59.');
            }
        } catch (\InvalidArgumentException $e) {
            throw new \DomainException($e->getMessage(), 0, $e);
        }

        if (null !== $orderWeights) {
            $settings->setOrderWeights($orderWeights);
        }
        if (null !== $alertSchedule) {
            $settings->setAlertSchedule($alertSchedule);
        }
        if (\is_string($command->morningTime)) {
            $settings->setMorningTime($command->morningTime);
        }
        if (\is_string($command->eveningTime)) {
            $settings->setEveningTime($command->eveningTime);
        }

        return $this->updateTaskSettings->execute($settings);
    }
}
