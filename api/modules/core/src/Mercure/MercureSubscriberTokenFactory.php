<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

use Maggie\Core\Entity\User;
use Symfony\Component\HttpFoundation\Cookie;

/**
 * Mints the JWT a browser or app presents to the Mercure hub to subscribe to
 * its own topics.
 *
 * Extracted from GoogleAuthController when the e2e test login appeared: two
 * sign-in paths handing out subscriber tokens that drift apart would mean
 * real-time updates behaving differently under test than in production —
 * the one thing an e2e stack must not do.
 */
final class MercureSubscriberTokenFactory
{
    private const TTL_SECONDS = 86400;

    public function __construct(
        private readonly string $mercureJwtSecret,
    ) {
    }

    public function createForUser(User $user): string
    {
        $header = $this->base64UrlEncode(json_encode(['typ' => 'JWT', 'alg' => 'HS256'], JSON_THROW_ON_ERROR));
        $payload = $this->base64UrlEncode(json_encode([
            'mercure' => ['subscribe' => ['/users/'.$user->getId().'/{topic}']],
            'exp' => time() + self::TTL_SECONDS,
        ], JSON_THROW_ON_ERROR));
        $signature = $this->base64UrlEncode(
            hash_hmac('sha256', $header.'.'.$payload, $this->mercureJwtSecret, true)
        );

        return $header.'.'.$payload.'.'.$signature;
    }

    /**
     * @param bool $secure false only on the plain-HTTP e2e stack — a Secure
     *                     cookie there is silently dropped by the browser, and
     *                     the journey loses every real-time assertion
     */
    public function createCookieForUser(User $user, bool $secure = true): Cookie
    {
        return Cookie::create('mercureAuthorization')
            ->withValue($this->createForUser($user))
            ->withPath('/.well-known/mercure')
            ->withSecure($secure)
            ->withHttpOnly(true)
            ->withSameSite('lax');
    }

    private function base64UrlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }
}
