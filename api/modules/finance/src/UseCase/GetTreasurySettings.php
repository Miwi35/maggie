<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Message\CreateTreasurySettingsCommand;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The user's treasury settings, laid down on first read as the safety cushion
 * is: no onboarding step, and the creation goes through the bus so the other
 * open screens hear of it.
 */
class GetTreasurySettings
{
    public function __construct(
        private readonly TreasurySettingsRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function forUser(User $user): TreasurySettings
    {
        $settings = $this->repository->findOneByUser($user);
        if (null !== $settings) {
            return $settings;
        }

        $created = $this->bus->dispatch(new CreateTreasurySettingsCommand((string) $user->getId()))
            ->last(HandledStamp::class)?->getResult();
        \assert($created instanceof TreasurySettings);

        return $created;
    }

    /** @return array{id: string, salaryExpectedDay: int|null} */
    public static function describe(TreasurySettings $settings): array
    {
        return [
            'id' => (string) $settings->getId(),
            'salaryExpectedDay' => $settings->getSalaryExpectedDay(),
        ];
    }
}
