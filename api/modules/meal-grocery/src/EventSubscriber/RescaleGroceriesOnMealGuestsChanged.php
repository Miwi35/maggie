<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\EventSubscriber;

use Maggie\Cookbook\Event\MealGuestsChanged;
use Maggie\MealGrocery\Message\RescaleMealGroceriesCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Turns the fact into a command; the rule lives in the use case. */
#[AsMessageHandler(bus: 'event.bus')]
final class RescaleGroceriesOnMealGuestsChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(MealGuestsChanged $event): void
    {
        $this->commandBus->dispatch(new RescaleMealGroceriesCommand($event->mealId, $event->previousGuests));
    }
}
