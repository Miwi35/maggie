<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Message\CreateMealSettingsCommand;
use Maggie\Cookbook\Repository\MealSettingsRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The user's meal settings, laid down on first read: no onboarding step, and
 * the creation goes through the bus so the other open screens hear of it.
 */
class GetMealSettings
{
    public function __construct(
        private readonly MealSettingsRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function forUser(User $user): MealSettings
    {
        $settings = $this->repository->findOneByUser($user);
        if (null !== $settings) {
            return $settings;
        }

        $created = $this->bus->dispatch(new CreateMealSettingsCommand((string) $user->getId()))
            ->last(HandledStamp::class)?->getResult();
        \assert($created instanceof MealSettings);

        return $created;
    }

    /**
     * The settings as the screens read them, with the default their
     * « Valeurs par défaut » button puts back.
     *
     * @return array<string, mixed>
     */
    public static function describe(MealSettings $settings): array
    {
        return [
            'id' => (string) $settings->getId(),
            'defaultGuests' => $settings->getDefaultGuests(),
            'defaults' => [
                'defaultGuests' => MealSettings::DEFAULT_GUESTS,
            ],
        ];
    }
}
