<?php

declare(strict_types=1);

namespace Maggie\Core\EventListener;

use Lexik\Bundle\JWTAuthenticationBundle\Event\AuthenticationSuccessEvent;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;

/**
 * The Mercure subscriber token lives as long as the JWT (24 h), but the app
 * only receives one at sign-in. Without a new one on each refresh, real-time
 * dies silently a day after login while every REST call keeps working.
 */
final class RefreshMercureTokenListener
{
    public function __construct(
        private readonly MercureSubscriberTokenFactory $mercureSubscriberTokenFactory,
    ) {
    }

    public function onAuthenticationSuccess(AuthenticationSuccessEvent $event): void
    {
        $user = $event->getUser();

        if (!$user instanceof User) {
            return;
        }

        $data = $event->getData();
        $data['mercureToken'] = $this->mercureSubscriberTokenFactory->createForUser($user);
        $event->setData($data);
    }
}
