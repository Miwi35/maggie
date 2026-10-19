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
            if (null !== $command->morningTime && (!\is_string($command->morningTime) || 1 !== preg_match(TaskSettings::TIME_PATTERN, $command->morningTime))) {
                throw new \InvalidArgumentException('morningTime must be a time "HH:MM" from 00:00 to 23:59.');
            }
            if (null !== $command->eveningTime && (!\is_string($command->eveningTime) || 1 !== preg_match(TaskSettings::TIME_PATTERN, $command->eveningTime))) {
                throw new \InvalidArgumentException('eveningTime must be a time "HH:MM" from 00:00 to 23:59.');
            }
            foreach (['rewardAnimation' => $command->rewardAnimation, 'rewardVibration' => $command->rewardVibration, 'rewardSound' => $command->rewardSound] as $field => $value) {
                if (null !== $value && !\is_bool($value)) {
                    throw new \InvalidArgumentException("{$field} must be true or false.");
                }
            }
            if (null !== $command->tone && !\in_array($command->tone, TaskSettings::TONES, true)) {
                throw new \InvalidArgumentException('tone must be one of '.implode(', ', TaskSettings::TONES).'.');
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
        if (\is_bool($command->rewardAnimation)) {
            $settings->setRewardAnimation($command->rewardAnimation);
        }
        if (\is_bool($command->rewardVibration)) {
            $settings->setRewardVibration($command->rewardVibration);
        }
        if (\is_bool($command->rewardSound)) {
            $settings->setRewardSound($command->rewardSound);
        }
        if (\is_string($command->tone)) {
            $settings->setTone($command->tone);
        }

        return $this->updateTaskSettings->execute($settings);
    }
}
