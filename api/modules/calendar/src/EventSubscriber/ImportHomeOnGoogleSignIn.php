<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Service\HomeFromGoogle;
use Maggie\Core\Event\UserSignedInWithGoogle;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * The sign-in has just asked for the Google addresses: if the user has no
 * « Maison » yet, take it from their account (MAG-664).
 */
#[AsMessageHandler(bus: 'event.bus')]
final class ImportHomeOnGoogleSignIn
{
    public function __construct(
        private readonly UserRepository $users,
        private readonly HomeFromGoogle $homeFromGoogle,
    ) {
    }

    public function __invoke(UserSignedInWithGoogle $event): void
    {
        $user = $this->users->find($event->userId);
        if (null !== $user) {
            $this->homeFromGoogle->import($user);
        }
    }
}
