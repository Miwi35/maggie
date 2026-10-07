<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\HttpFoundation\Cookie;

/**
 * Builds the httpOnly cookie carrying the refresh token for the web admin.
 *
 * Gesdinet sets this cookie itself when a token is refreshed. The Google
 * callback and the e2e login mint a first token outside that flow, so they
 * build the same cookie from the bundle's own settings: one name, path and
 * Secure flag, or the browser would hold two cookies and the refresh route
 * would read whichever it saw first.
 */
final class RefreshTokenCookieFactory
{
    /**
     * @param array<string, mixed> $settings
     */
    public function __construct(
        #[Autowire('%gesdinet_jwt_refresh_token.cookie%')]
        private readonly array $settings,
        #[Autowire('%gesdinet_jwt_refresh_token.ttl%')]
        private readonly int $ttl,
        #[Autowire('%gesdinet_jwt_refresh_token.token_parameter_name%')]
        private readonly string $name,
    ) {
    }

    public function create(string $refreshToken): Cookie
    {
        return new Cookie(
            $this->name,
            $refreshToken,
            time() + $this->ttl,
            (string) ($this->settings['path'] ?? '/'),
            $this->settings['domain'] ?? null,
            $this->isSecure(),
            filter_var($this->settings['http_only'] ?? true, FILTER_VALIDATE_BOOLEAN),
            false,
            (string) ($this->settings['same_site'] ?? 'lax'),
            filter_var($this->settings['partitioned'] ?? false, FILTER_VALIDATE_BOOLEAN),
        );
    }

    /**
     * False only on the plain-HTTP e2e stack, where the bundle is configured
     * the same way.
     */
    public function isSecure(): bool
    {
        return filter_var($this->settings['secure'] ?? true, FILTER_VALIDATE_BOOLEAN);
    }
}
