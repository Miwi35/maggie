<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\EventSubscriber;

use Maggie\Cookbook\Event\MealRemoved;
use Maggie\MealGrocery\Doctrine\MealRemovalLedger;
use Maggie\MealGrocery\Message\RevokeMealGroceriesCommand;
use Maggie\Notification\Message\DeleteNotificationCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A removed meal takes its share off the list and its shortage notifications off
 * the notification list. The rules live in the use case and the notification module.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class RevokeGroceriesOnMealRemoved
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly MealRemovalLedger $ledger,
    ) {
    }

    public function __invoke(MealRemoved $event): void
    {
        $given = $this->ledger->take($event->mealId);

        foreach ($given['notificationIds'] as $notificationId) {
            $this->commandBus->dispatch(new DeleteNotificationCommand(notificationId: $notificationId));
        }

        $this->commandBus->dispatch(new RevokeMealGroceriesCommand($event->mealId, $event->userId, $given['contributions']));
    }
}
