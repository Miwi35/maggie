<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Message\CreateTaskSettingsCommand;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Core\Entity\User;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The user's task settings, laid down on first read: no onboarding step, and
 * the creation goes through the bus so the other open screens hear of it.
 */
class GetTaskSettings
{
    public function __construct(
        private readonly TaskSettingsRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function forUser(User $user): TaskSettings
    {
        $settings = $this->repository->findOneByUser($user);
        if (null !== $settings) {
            return $settings;
        }

        $created = $this->bus->dispatch(new CreateTaskSettingsCommand((string) $user->getId()))
            ->last(HandledStamp::class)?->getResult();
        \assert($created instanceof TaskSettings);

        return $created;
    }

    /**
     * The settings as the screens read them, with the defaults their
     * « Valeurs par défaut » button puts back.
     *
     * @return array<string, mixed>
     */
    public static function describe(TaskSettings $settings): array
    {
        return [
            'id' => (string) $settings->getId(),
            'orderWeights' => $settings->getOrderWeights(),
            'orderLevels' => TaskOrderWeights::levels($settings->getOrderWeights()),
            'alertSchedule' => $settings->getAlertSchedule(),
            'morningTime' => $settings->getMorningTime(),
            'eveningTime' => $settings->getEveningTime(),
            'rewardAnimation' => $settings->isRewardAnimation(),
            'rewardVibration' => $settings->isRewardVibration(),
            'rewardSound' => $settings->isRewardSound(),
            'tone' => $settings->getTone(),
            'dayMoments' => $settings->getDayMoments(),
            'reminderDailyCap' => $settings->getReminderDailyCap(),
            'quietStart' => $settings->getQuietStart(),
            'quietEnd' => $settings->getQuietEnd(),
            'defaults' => [
                'orderWeights' => TaskOrderWeights::DEFAULTS,
                'orderLevels' => array_map(static fn (): int => TaskOrderWeights::DEFAULT_LEVEL, TaskOrderWeights::DEFAULTS),
                'alertSchedule' => TaskAlertSchedule::DEFAULTS,
                'morningTime' => TaskSettings::DEFAULT_MORNING_TIME,
                'eveningTime' => TaskSettings::DEFAULT_EVENING_TIME,
                'rewardAnimation' => true,
                'rewardVibration' => true,
                'rewardSound' => true,
                'tone' => TaskSettings::DEFAULT_TONE,
                'dayMoments' => DayMoment::DEFAULTS,
                'reminderDailyCap' => TaskSettings::DEFAULT_REMINDER_DAILY_CAP,
                'quietStart' => TaskSettings::DEFAULT_QUIET_START,
                'quietEnd' => TaskSettings::DEFAULT_QUIET_END,
            ],
        ];
    }
}
