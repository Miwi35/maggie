<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Bank;

use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * The provider authenticates every call with a JWT signed by our own key, so
 * what matters here is the shape of that token and that failures are reported
 * rather than swallowed.
 */
class EnableBankingClientTest extends TestCase
{
    private string $keyPath;

    protected function setUp(): void
    {
        // A throwaway key: the test signs with it, never with the real one.
        $this->keyPath = tempnam(sys_get_temp_dir(), 'eb-key-');
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        file_put_contents($this->keyPath, $pem);
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
    }

    private function client(MockHttpClient $http): EnableBankingClient
    {
        return new EnableBankingClient($http, 'app-id-1234', $this->keyPath, 'https://api.example.test');
    }

    /** @return array{header: array<string, mixed>, claims: array<string, mixed>} */
    private function decodeToken(string $authorization): array
    {
        $jwt = substr($authorization, \strlen('Bearer '));
        [$header, $claims] = explode('.', $jwt);

        $decode = static fn (string $part): array => json_decode(
            (string) base64_decode(strtr($part, '-_', '+/'), true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        return ['header' => $decode($header), 'claims' => $decode($claims)];
    }

    public function testEveryCallCarriesAJwtSignedForThisApplication(): void
    {
        $seen = null;
        $http = new MockHttpClient(function (string $method, string $url, array $options) use (&$seen) {
            $seen = $options;

            return new MockResponse(json_encode(['aspsps' => []]), [
                'response_headers' => ['content-type' => 'application/json'],
            ]);
        });

        $this->client($http)->listBanks('FR');

        $authorization = null;
        foreach ($seen['headers'] as $header) {
            if (str_starts_with($header, 'Authorization: ')) {
                $authorization = substr($header, \strlen('Authorization: '));
            }
        }

        self::assertNotNull($authorization, 'the request carries no Authorization header');

        $token = $this->decodeToken($authorization);

        self::assertSame('JWT', $token['header']['typ']);
        self::assertSame('RS256', $token['header']['alg']);
        // The key id is how the provider knows which public key to verify with.
        self::assertSame('app-id-1234', $token['header']['kid']);

        self::assertSame('enablebanking.com', $token['claims']['iss']);
        self::assertSame('api.enablebanking.com', $token['claims']['aud']);
        // A short life: a leaked token is worth nothing for long.
        self::assertLessThanOrEqual(86400, $token['claims']['exp'] - $token['claims']['iat']);
        self::assertGreaterThan(0, $token['claims']['exp'] - $token['claims']['iat']);
    }

    public function testTheSignatureVerifiesAgainstThePublicKey(): void
    {
        $seen = null;
        $http = new MockHttpClient(function (string $method, string $url, array $options) use (&$seen) {
            $seen = $options;

            return new MockResponse(json_encode(['aspsps' => []]));
        });

        $this->client($http)->listBanks('FR');

        $authorization = '';
        foreach ($seen['headers'] as $header) {
            if (str_starts_with($header, 'Authorization: ')) {
                $authorization = substr($header, \strlen('Authorization: '));
            }
        }

        [$header, $claims, $signature] = explode('.', substr($authorization, 7));
        $raw = base64_decode(strtr($signature, '-_', '+/'), true);

        $verified = openssl_verify(
            $header . '.' . $claims,
            (string) $raw,
            openssl_pkey_get_public(openssl_pkey_get_details(
                openssl_pkey_get_private((string) file_get_contents($this->keyPath)),
            )['key']),
            OPENSSL_ALGO_SHA256,
        );

        self::assertSame(1, $verified);
    }

    public function testItAsksForTheCountryAndAudienceItWasGiven(): void
    {
        $url = null;
        $http = new MockHttpClient(function (string $method, string $requested) use (&$url) {
            $url = $requested;

            return new MockResponse(json_encode(['aspsps' => [['name' => 'BBVA']]]));
        });

        $banks = $this->client($http)->listBanks('fr', 'business');

        self::assertStringContainsString('https://api.example.test/aspsps', (string) $url);
        self::assertStringContainsString('country=FR', (string) $url);
        self::assertStringContainsString('psu_type=business', (string) $url);
        self::assertSame('BBVA', $banks[0]['name']);
    }

    public function testTransactionsArePagedWithTheContinuationKey(): void
    {
        $url = null;
        $http = new MockHttpClient(function (string $method, string $requested) use (&$url) {
            $url = $requested;

            return new MockResponse(json_encode(['transactions' => []]));
        });

        $this->client($http)->listTransactions(
            'account-1',
            new \DateTimeImmutable('2026-01-01'),
            new \DateTimeImmutable('2026-03-31'),
            'page-2',
        );

        self::assertStringContainsString('/accounts/account-1/transactions', (string) $url);
        self::assertStringContainsString('date_from=2026-01-01', (string) $url);
        self::assertStringContainsString('date_to=2026-03-31', (string) $url);
        self::assertStringContainsString('continuation_key=page-2', (string) $url);
    }

    public function testARefusalIsReportedWithWhatTheProviderSaid(): void
    {
        $http = new MockHttpClient(new MockResponse(
            json_encode(['message' => 'Invalid signature']),
            ['http_code' => 401],
        ));

        $this->expectException(\RuntimeException::class);
        $this->expectExceptionMessageMatches('/401.*Invalid signature/');

        $this->client($http)->listBanks('FR');
    }

    public function testAMissingKeyIsReportedBeforeAnyCallIsMade(): void
    {
        $client = new EnableBankingClient(
            new MockHttpClient(new MockResponse('{}')),
            'app-id-1234',
            '/nowhere/absent.pem',
        );

        $this->expectException(\RuntimeException::class);
        $this->expectExceptionMessageMatches('/private key is missing/');

        $client->listBanks('FR');
    }
}
