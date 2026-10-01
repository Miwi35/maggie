<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

use Lcobucci\JWT\Configuration;
use Lcobucci\JWT\Signer\Hmac\Sha256;
use Lcobucci\JWT\Signer\Key\InMemory;

/**
 * Mints the OAuth 2.0 access tokens the Mercure 1.0 hub expects (RFC 9068 +
 * RFC 9396): `typ: at+jwt`, an `iss` the hub trusts, an `aud` equal to its
 * pinned `resource_identifier`, a required `exp`, and the grants as
 * `authorization_details`. The 0.x `mercure.publish` / `mercure.subscribe`
 * claim is refused with a 401.
 *
 * `aud` is MERCURE_PUBLIC_URL, which the hub is started with as
 * `resource_identifier`: a hub reached by Traefik over plain HTTP cannot
 * derive the public URL a token should name, so it is pinned. The agent mints
 * its publisher tokens the same way (agent/app/mercure/publisher.py).
 *
 * Signing with a key under 256 bits throws a Lcobucci\JWT\Exception, which
 * MercurePublishMiddleware reports as `critical`.
 */
final class MercureAccessToken
{
    public const ISSUER = 'maggie';

    private const DETAIL_TYPE = 'https://mercure.rocks/authorization-detail';

    private const PUBLISHER_TTL_SECONDS = 3600;

    public function __construct(
        private readonly string $mercureJwtSecret,
        private readonly string $mercurePublicUrl,
    ) {
    }

    /** Publish on every topic. The API publishes for every user. */
    public function forPublisher(): string
    {
        return $this->mint(
            'maggie-api',
            self::PUBLISHER_TTL_SECONDS,
            [$this->detail('publish', [['match' => '*']])],
        );
    }

    /**
     * @param list<string> $exactTopics   granted as exact matches
     * @param list<string> $topicPatterns granted as URL Patterns (`/users/<id>/*`)
     */
    public function forSubscriber(string $subject, array $exactTopics, array $topicPatterns, int $ttlSeconds): string
    {
        $topics = [
            ...array_map(static fn (string $topic): array => ['match' => $topic], $exactTopics),
            ...array_map(static fn (string $pattern): array => ['match' => $pattern, 'match_type' => 'urlpattern'], $topicPatterns),
        ];

        return $this->mint($subject, $ttlSeconds, [$this->detail('subscribe', $topics)]);
    }

    /**
     * @param list<array<string, string>> $topics
     *
     * @return array<string, mixed>
     */
    private function detail(string $action, array $topics): array
    {
        return ['type' => self::DETAIL_TYPE, 'actions' => [$action], 'topics' => $topics];
    }

    /**
     * @param list<array<string, mixed>> $details
     */
    private function mint(string $subject, int $ttlSeconds, array $details): string
    {
        $config = Configuration::forSymmetricSigner(new Sha256(), InMemory::plainText($this->mercureJwtSecret));
        $now = new \DateTimeImmutable();

        return $config->builder()
            ->withHeader('typ', 'at+jwt')
            ->issuedBy(self::ISSUER)
            ->permittedFor($this->mercurePublicUrl)
            ->relatedTo($subject)
            ->withClaim('client_id', self::ISSUER)
            ->identifiedBy('urn:uuid:'.self::uuid())
            ->issuedAt($now)
            ->expiresAt($now->modify('+'.$ttlSeconds.' seconds'))
            ->withClaim('authorization_details', $details)
            ->getToken($config->signer(), $config->signingKey())
            ->toString();
    }

    private static function uuid(): string
    {
        $bytes = random_bytes(16);
        $bytes[6] = chr((ord($bytes[6]) & 0x0F) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3F) | 0x80);

        return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($bytes), 4));
    }
}
