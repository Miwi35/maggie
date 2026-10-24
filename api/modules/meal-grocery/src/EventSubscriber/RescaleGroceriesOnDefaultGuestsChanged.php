<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\EventSubscriber;

use Maggie\Cookbook\Event\DefaultGuestsChanged;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Repository\UserRepository;
use Maggie\MealGrocery\Message\RescaleMealGroceriesCommand;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/** Scales the list for each meal still to be eaten that follows the default number of guests. */
#[AsMessageHandler(bus: 'event.bus')]
final class RescaleGroceriesOnDefaultGuestsChanged
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly MealRepository $meals,
        private readonly UserRepository $users,
    ) {
    }

    public function __invoke(DefaultGuestsChanged $event): void
    {
        $user = $this->users->find($event->userId);
        if (null === $user) {
            return;
        }

        foreach ($this->meals->findUpcomingOnDefaultGuestsForUser($user) as $meal) {
            $this->commandBus->dispatch(new RescaleMealGroceriesCommand((string) $meal->getId(), $event->previousDefault));
        }
    }
}
