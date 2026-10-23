<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Finance\Event\FinanceFiguresChanged;
use Maggie\Finance\Message\MarkFinanceDashboardStaleCommand;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** The Finance Dashboard read model is out of date as soon as the figures it is made of change (MAG-545). */
final class MarkFinanceDashboardStaleOnFiguresChanged
{
    public function __construct(
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function __invoke(FinanceFiguresChanged $event): void
    {
        $this->commandBus->dispatch(new MarkFinanceDashboardStaleCommand($event->userId));
    }
}
