<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Message\CreateTaskSettingsCommand;
use Maggie\Calendar\Repository\TaskSettingsRepository;
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

    /** @return array{id: string, orderWeights: array<string, array<string, int>>} */
    public static function describe(TaskSettings $settings): array
    {
        return [
            'id' => (string) $settings->getId(),
            'orderWeights' => $settings->getOrderWeights(),
        ];
    }
}
