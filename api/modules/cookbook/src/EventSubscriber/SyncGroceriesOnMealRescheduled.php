<?php

declare(strict_types=1);

namespace Maggie\Cookbook\EventSubscriber;

use Maggie\Cookbook\Event\MealRescheduled;
use Maggie\Cookbook\Message\SyncMealGroceriesCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class SyncGroceriesOnMealRescheduled
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(MealRescheduled $event): void
    {
        $this->commandBus->dispatch(new SyncMealGroceriesCommand($event->mealId));
    }
}
