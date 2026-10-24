<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\EventSubscriber;

use Maggie\Cookbook\Event\MealPassed;
use Maggie\MealGrocery\Message\ReleaseMealGroceriesCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class ReleaseGroceriesOnMealPassed
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(MealPassed $event): void
    {
        $this->commandBus->dispatch(new ReleaseMealGroceriesCommand($event->mealId));
    }
}
