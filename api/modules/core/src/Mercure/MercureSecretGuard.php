<?php

declare(strict_types=1);

namespace Maggie\Core\Mercure;

/**
 * HS256 needs at least 256 bits of key. A shorter MERCURE_JWT_SECRET makes
 * every publication fail while the request itself succeeds, so real-time
 * silently stops — this refuses it at startup instead (MAG-141).
 */
final class MercureSecretGuard
{
    public const MIN_BYTES = 32;

    public function __construct(
        private readonly string $mercureJwtSecret,
    ) {
    }

    public function assertValid(): void
    {
        $length = strlen($this->mercureJwtSecret);

        if ($length < self::MIN_BYTES) {
            throw new InvalidMercureSecretException(sprintf('MERCURE_JWT_SECRET is %d bytes long but HS256 requires at least %d bytes (256 bits); without it no real-time update can be published. Generate one with `openssl rand -hex 32` and deploy it to the API, the agent and the Mercure hub together.', $length, self::MIN_BYTES));
        }
    }
}
