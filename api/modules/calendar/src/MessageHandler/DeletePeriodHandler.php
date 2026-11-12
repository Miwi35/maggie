<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Message\DeletePeriodCommand;
use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Calendar\UseCase\DeletePeriod;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeletePeriodHandler
{
    public function __construct(
        private readonly DeletePeriod $deletePeriod,
        private readonly PeriodRepository $periodRepository,
    ) {
    }

    public function __invoke(DeletePeriodCommand $command): void
    {
        $period = $this->periodRepository->findOneOfUser($command->userId, $command->periodId)
            ?? throw new \DomainException("Period not found: {$command->periodId}");

        $this->deletePeriod->execute($period);
    }
}
