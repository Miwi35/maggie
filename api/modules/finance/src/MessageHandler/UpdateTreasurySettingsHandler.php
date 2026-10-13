<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Message\UpdateTreasurySettingsCommand;
use Maggie\Finance\Repository\TreasurySettingsRepository;
use Maggie\Finance\UseCase\UpdateTreasurySettings;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateTreasurySettingsHandler
{
    public function __construct(
        private readonly UpdateTreasurySettings $updateTreasurySettings,
        private readonly TreasurySettingsRepository $repository,
    ) {
    }

    public function __invoke(UpdateTreasurySettingsCommand $command): TreasurySettings
    {
        $settings = $this->repository->findOneBy(['id' => $command->treasurySettingsId, 'user' => $command->userId])
            ?? throw new \DomainException("Treasury settings not found: {$command->treasurySettingsId}");

        if ($command->changesSalaryExpectedDay) {
            $day = $command->salaryExpectedDay;
            if (null !== $day && ($day < 1 || $day > 31)) {
                throw new \DomainException('salaryExpectedDay is a day of the month, from 1 to 31.');
            }
            $settings->setSalaryExpectedDay($day);
        }

        if (null !== $command->overdraftLimitCents) {
            if ($command->overdraftLimitCents < 0) {
                throw new \DomainException('overdraftLimitCents is the overdraft the bank allows, 0 or more cents.');
            }
            $settings->setOverdraftLimitCents($command->overdraftLimitCents);
        }

        return $this->updateTreasurySettings->execute($settings);
    }
}
