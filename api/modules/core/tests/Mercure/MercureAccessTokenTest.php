<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mercure;

use Lcobucci\JWT\Exception as JwtException;
use Maggie\Core\Mercure\MercureAccessToken;
use Maggie\Core\Mercure\MercurePublisherTokenProvider;
use PHPUnit\Framework\TestCase;

final class MercureAccessTokenTest extends TestCase
{
    private const SECRET = 'a-mercure-secret-of-at-least-32-bytes';
    private const PUBLIC_URL = 'https://maggie.test/.well-known/mercure';

    public function testThePublisherTokenIsAnOAuthAccessTokenTheHubTrusts(): void
    {
        ['header' => $header, 'claims' => $claims] = self::decode($this->minter()->forPublisher());

        self::assertSame('at+jwt', $header['typ']);
        self::assertSame('HS256', $header['alg']);
        self::assertSame(MercureAccessToken::ISSUER, $claims['iss']);
        self::assertSame(self::PUBLIC_URL, $claims['aud'], 'aud must equal the hub\'s pinned resource_identifier');
        self::assertGreaterThan(time(), $claims['exp']);
        self::assertSame(
            [['type' => 'https://mercure.rocks/authorization-detail', 'actions' => ['publish'], 'topics' => [['match' => '*']]]],
            $claims['authorization_details'],
        );
    }

    public function testThePublisherTokenIsSignedWithTheSharedSecret(): void
    {
        [$header, $payload, $signature] = explode('.', $this->minter()->forPublisher());

        $expected = rtrim(strtr(base64_encode(hash_hmac('sha256', $header.'.'.$payload, self::SECRET, true)), '+/', '-_'), '=');

        self::assertSame($expected, $signature);
    }

    public function testTheLegacyClaimIsGone(): void
    {
        self::assertArrayNotHasKey('mercure', self::decode($this->minter()->forPublisher())['claims']);
    }

    public function testTheBundleGetsItsPublisherTokenFromTheProvider(): void
    {
        $jwt = (new MercurePublisherTokenProvider($this->minter()))->getJwt();

        self::assertSame(['publish'], self::decode($jwt)['claims']['authorization_details'][0]['actions']);
    }

    public function testASecretUnder256BitsCannotSign(): void
    {
        $this->expectException(JwtException::class);

        (new MercureAccessToken('too-short', self::PUBLIC_URL))->forPublisher();
    }

    private function minter(): MercureAccessToken
    {
        return new MercureAccessToken(self::SECRET, self::PUBLIC_URL);
    }

    /**
     * @return array{header: array<string, mixed>, claims: array<string, mixed>}
     */
    public static function decode(string $jwt): array
    {
        $part = static fn (string $segment): array => json_decode(
            (string) base64_decode(strtr($segment, '-_', '+/'), true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
        $segments = explode('.', $jwt);

        return ['header' => $part($segments[0]), 'claims' => $part($segments[1])];
    }
}
