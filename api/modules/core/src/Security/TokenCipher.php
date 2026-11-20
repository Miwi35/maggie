<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

use Symfony\Component\DependencyInjection\Attribute\Autowire;

/**
 * Seals a connected-service token at rest (MAG-889): AES-256-GCM, the context
 * — record, owner, usage — as additional data, so a sealed value copied
 * elsewhere does not open. The key lives outside the database; without it
 * nothing is sealed and nothing is handed out.
 */
final class TokenCipher
{
    public const KEY_BYTES = 32;
    private const PREFIX = 'v1.';
    private const CIPHER = 'aes-256-gcm';
    private const NONCE_BYTES = 12;
    private const TAG_BYTES = 16;

    private readonly ?string $key;
    private readonly string $unavailableReason;

    public function __construct(
        #[Autowire('%env(default::TOKEN_ENCRYPTION_KEY)%')]
        ?string $key,
    ) {
        $decoded = null === $key || '' === $key ? false : base64_decode($key, true);

        if (false !== $decoded && self::KEY_BYTES === strlen($decoded)) {
            $this->key = $decoded;
            $this->unavailableReason = '';

            return;
        }

        $this->key = null;
        $this->unavailableReason = null === $key || '' === $key
            ? 'TOKEN_ENCRYPTION_KEY is not set: connected-service tokens can be neither sealed nor opened. Generate one with `openssl rand -base64 32` and add it to the API environment (prod: the maggie-env Secret).'
            : sprintf('TOKEN_ENCRYPTION_KEY must be the base64 of %d bytes but decodes to %s. Generate one with `openssl rand -base64 32`.', self::KEY_BYTES, false === $decoded ? 'no valid base64' : strlen($decoded).' bytes');
    }

    public function isAvailable(): bool
    {
        return null !== $this->key;
    }

    public function seal(string $plain, string $context): string
    {
        $key = $this->key();
        $nonce = random_bytes(self::NONCE_BYTES);
        $tag = '';
        $sealed = openssl_encrypt($plain, self::CIPHER, $key, OPENSSL_RAW_DATA, $nonce, $tag, $context, self::TAG_BYTES);

        if (false === $sealed) {
            throw new TokenCipherUnavailable('OpenSSL could not seal the token with aes-256-gcm.');
        }

        return self::PREFIX.self::base64UrlEncode($nonce.$tag.$sealed);
    }

    public function open(string $sealed, string $context): string
    {
        $key = $this->key();

        if (!self::isSealed($sealed)) {
            throw new TokenUnreadable('The stored token is not sealed with a known format (expected the v1. prefix).');
        }

        $raw = self::base64UrlDecode(substr($sealed, strlen(self::PREFIX)));
        if (null === $raw || strlen($raw) < self::NONCE_BYTES + self::TAG_BYTES) {
            throw new TokenUnreadable('The stored token is truncated or not valid base64url.');
        }

        $plain = openssl_decrypt(
            substr($raw, self::NONCE_BYTES + self::TAG_BYTES),
            self::CIPHER,
            $key,
            OPENSSL_RAW_DATA,
            substr($raw, 0, self::NONCE_BYTES),
            substr($raw, self::NONCE_BYTES, self::TAG_BYTES),
            $context,
        );

        if (false === $plain) {
            throw new TokenUnreadable('The stored token does not open: altered, sealed for another record, owner or usage, or under another key.');
        }

        return $plain;
    }

    public static function isSealed(string $value): bool
    {
        return str_starts_with($value, self::PREFIX);
    }

    private function key(): string
    {
        return $this->key ?? throw new TokenCipherUnavailable($this->unavailableReason);
    }

    private static function base64UrlEncode(string $bytes): string
    {
        return rtrim(strtr(base64_encode($bytes), '+/', '-_'), '=');
    }

    private static function base64UrlDecode(string $encoded): ?string
    {
        $decoded = base64_decode(strtr($encoded, '-_', '+/'), true);

        return false === $decoded ? null : $decoded;
    }
}
