<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\RefreshFinanceDashboardCommand;
use Maggie\Finance\UseCase\RefreshFinanceDashboard;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RefreshFinanceDashboardHandler
{
    public function __construct(
        private readonly RefreshFinanceDashboard $refreshFinanceDashboard,
    ) {
    }

    public function __invoke(RefreshFinanceDashboardCommand $command): void
    {
        $this->refreshFinanceDashboard->execute($command->userId);
    }
}
