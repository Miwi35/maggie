<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Core\Event\UserSignedInWithGoogle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Refreshes the tasks of the list the owner chose in Paramètres › Google. A
 * first sign-in has chosen none, and the pull does nothing until it has: the
 * list is the owner's to pick, never ours to adopt (MAG-118).
 */
#[AsMessageHandler(bus: 'event.bus')]
final class PullTasksOnGoogleSignIn
{
    public function __construct(
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
    ) {
    }

    public function __invoke(UserSignedInWithGoogle $event): void
    {
        $this->commandBus->dispatch(new PullTasksFromGoogleCommand(userId: $event->userId));
    }
}
