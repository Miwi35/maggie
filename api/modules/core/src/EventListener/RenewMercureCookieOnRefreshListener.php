<?php

declare(strict_types=1);

namespace Maggie\Core\EventListener;

use Lexik\Bundle\JWTAuthenticationBundle\Event\AuthenticationSuccessEvent;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Security\RefreshTokenCookieFactory;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;

/**
 * Keeps the Mercure session aligned with the API session.
 *
 * The subscriber cookie is a JWT that expires with the 24 h access token. A
 * wall tablet that stays signed in for weeks must get a fresh one every time
 * its access token is renewed, or real-time updates stop at the first expiry
 * while the rest of the app keeps working.
 *
 * Only the refresh route reaches this event: there is no other
 * authentication-success path in this API.
 */
#[AsEventListener(event: 'lexik_jwt_authentication.on_authentication_success')]
final class RenewMercureCookieOnRefreshListener
{
    public function __construct(
        private readonly MercureSubscriberTokenFactory $mercureSubscriberTokenFactory,
        private readonly RefreshTokenCookieFactory $refreshTokenCookieFactory,
    ) {
    }

    public function __invoke(AuthenticationSuccessEvent $event): void
    {
        $user = $event->getUser();

        if (!$user instanceof User) {
            return;
        }

        $event->getResponse()->headers->setCookie(
            $this->mercureSubscriberTokenFactory->createCookieForUser($user, $this->refreshTokenCookieFactory->isSecure())
        );
    }
}
