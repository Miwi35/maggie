<?php

declare(strict_types=1);

namespace Maggie\Core\EventListener;

use Lexik\Bundle\JWTAuthenticationBundle\Event\JWTCreatedEvent;
use Maggie\Core\Entity\User;

final class JWTCreatedListener
{
    public function onJWTCreated(JWTCreatedEvent $event): void
    {
        $user = $event->getUser();

        if ($user instanceof User) {
            $payload = $event->getData();
            $payload['sub'] = (string) $user->getId();
            $event->setData($payload);
        }
    }
}
