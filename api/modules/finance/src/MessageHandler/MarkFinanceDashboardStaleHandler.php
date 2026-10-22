<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\MarkFinanceDashboardStaleCommand;
use Maggie\Finance\UseCase\MarkFinanceDashboardStale;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class MarkFinanceDashboardStaleHandler
{
    public function __construct(
        private readonly MarkFinanceDashboardStale $markFinanceDashboardStale,
    ) {
    }

    public function __invoke(MarkFinanceDashboardStaleCommand $command): void
    {
        $this->markFinanceDashboardStale->execute($command->userId);
    }
}
