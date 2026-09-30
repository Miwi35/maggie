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

    /**
     * Topics the agent publishes, keyed by the user id rather than scoped
     * under /users/{id}. Updates are private, so a token only receives what
     * one of its selectors names.
     */
    private const AGENT_TOPICS = ['chat', 'contexts', 'proactions', 'instructions', 'skills'];

    public function __construct(
        private readonly string $mercureJwtSecret,
    ) {
    }

    public function createForUser(User $user): string
    {
        $header = $this->base64UrlEncode(json_encode(['typ' => 'JWT', 'alg' => 'HS256'], JSON_THROW_ON_ERROR));
        $payload = $this->base64UrlEncode(json_encode([
            'mercure' => ['subscribe' => $this->subscribeSelectors($user)],
            'exp' => time() + self::TTL_SECONDS,
        ], JSON_THROW_ON_ERROR));
        $signature = $this->base64UrlEncode(
            hash_hmac('sha256', $header.'.'.$payload, $this->mercureJwtSecret, true)
        );

        return $header.'.'.$payload.'.'.$signature;
    }

    /**
     * `{+topic}` (reserved expansion) crosses "/" where `{topic}` does not, so
     * it covers /users/{id}/api/tasks/{id}; with private updates a selector
     * that matches nothing silences every real-time surface.
     *
     * @return list<string>
     */
    private function subscribeSelectors(User $user): array
    {
        $id = (string) $user->getId();

        return [
            '/users/'.$id.'/{+topic}',
            ...array_map(static fn (string $topic): string => '/'.$topic.'/'.$id, self::AGENT_TOPICS),
        ];
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
