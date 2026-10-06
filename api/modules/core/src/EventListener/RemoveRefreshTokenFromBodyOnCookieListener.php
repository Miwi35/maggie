<?php

declare(strict_types=1);

namespace Maggie\Core\EventListener;

use Lexik\Bundle\JWTAuthenticationBundle\Event\AuthenticationSuccessEvent;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpFoundation\RequestStack;

/**
 * Keeps the refresh token out of the JSON body of a web session.
 *
 * The web admin sends the token through its httpOnly cookie. Gesdinet also
 * echoes the rotated token in the body, where an injected script could read it
 * with a credentialed fetch and leave with a 30-day access, which cancels what
 * httpOnly is for. The mobile app has no cookie jar: it sends the token in the
 * body and keeps receiving it there.
 *
 * Runs after Gesdinet's own listener (priority 0), which is the one that adds
 * the token to the data and sets the cookie.
 */
#[AsEventListener(event: 'lexik_jwt_authentication.on_authentication_success', priority: -10)]
final class RemoveRefreshTokenFromBodyOnCookieListener
{
    private const TOKEN_PARAMETER = 'refresh_token';

    public function __construct(private readonly RequestStack $requestStack)
    {
    }

    public function __invoke(AuthenticationSuccessEvent $event): void
    {
        $request = $this->requestStack->getCurrentRequest();

        if (null === $request || !$request->cookies->has(self::TOKEN_PARAMETER)) {
            return;
        }

        if ($this->bodyCarriesToken($request->getContent())) {
            return;
        }

        $data = $event->getData();
        unset($data[self::TOKEN_PARAMETER]);
        $event->setData($data);
    }

    private function bodyCarriesToken(string $content): bool
    {
        $body = json_decode($content, true);

        return is_array($body) && isset($body[self::TOKEN_PARAMETER]) && '' !== $body[self::TOKEN_PARAMETER];
    }
}
