<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

use Symfony\Component\Mercure\Jwt\TokenProviderInterface;

/**
 * The bundle's own `jwt.secret` signs the 0.x `mercure.publish` claim, which a
 * 1.0 hub refuses; this provider hands it the OAuth 2.0 access token instead
 * (config/packages/mercure.yaml, `jwt.provider`).
 */
final class MercurePublisherTokenProvider implements TokenProviderInterface
{
    public function __construct(
        private readonly MercureAccessToken $accessToken,
    ) {
    }

    public function getJwt(): string
    {
        return $this->accessToken->forPublisher();
    }
}
