<?php

declare(strict_types=1);

namespace Maggie\Finance\Bank\EnableBanking;

use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Talks to Enable Banking.
 *
 * Every call carries a JWT signed with the application's own RSA key rather
 * than a shared secret: the key never leaves the server, and a leaked token is
 * worth nothing past its expiry.
 */
class EnableBankingClient
{
    /** Their ceiling is a day; an hour is plenty and limits the blast radius. */
    private const TOKEN_TTL_SECONDS = 3600;

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $applicationId,
        private readonly string $privateKeyPath,
        private readonly string $baseUrl = 'https://api.enablebanking.com',
    ) {
    }

    /**
     * Banks reachable in a country, as the provider names them.
     *
     * @return array<int, array<string, mixed>>
     */
    public function listBanks(string $country, string $psuType = 'personal'): array
    {
        $response = $this->request('GET', '/aspsps', [
            'query' => ['country' => strtoupper($country), 'psu_type' => $psuType],
        ]);

        return $response['aspsps'] ?? [];
    }

    /**
     * Opens the consent journey: the answer carries the URL the person has to
     * visit at their bank.
     *
     * @param array<string, mixed> $payload
     *
     * @return array<string, mixed>
     */
    public function startAuthorization(array $payload): array
    {
        return $this->request('POST', '/auth', ['json' => $payload]);
    }

    /**
     * Turns the one-time code the bank sent back into a session, which is what
     * later calls read accounts and movements from.
     *
     * @return array<string, mixed>
     */
    public function createSession(string $code): array
    {
        return $this->request('POST', '/sessions', ['json' => ['code' => $code]]);
    }

    /** @return array<string, mixed> */
    public function getSession(string $sessionId): array
    {
        return $this->request('GET', '/sessions/'.urlencode($sessionId));
    }

    /**
     * One page of movements. The provider paginates with a continuation key
     * rather than an offset, so the caller loops until it stops sending one.
     *
     * @param array<string, string> $psuHeaders sent when a person is waiting on
     *                                          the answer, which exempts the
     *                                          call from the bank's daily cap
     *
     * @return array<string, mixed>
     */
    public function listTransactions(
        string $accountId,
        ?\DateTimeImmutable $from = null,
        ?\DateTimeImmutable $to = null,
        ?string $continuationKey = null,
        array $psuHeaders = [],
    ): array {
        $query = array_filter([
            'date_from' => $from?->format('Y-m-d'),
            'date_to' => $to?->format('Y-m-d'),
            'continuation_key' => $continuationKey,
        ], static fn (?string $value) => null !== $value);

        return $this->request(
            'GET',
            sprintf('/accounts/%s/transactions', urlencode($accountId)),
            ['query' => $query],
            $psuHeaders,
        );
    }

    /**
     * What the bank says the account holds right now.
     *
     * Worth its own call: a synced window covers the last months, never the
     * whole life of the account, so a balance summed from the movements we
     * hold would be wrong by everything that came before.
     *
     * @param array<string, string> $psuHeaders
     *
     * @return array<string, mixed>
     */
    public function getBalances(string $accountId, array $psuHeaders = []): array
    {
        return $this->request(
            'GET',
            sprintf('/accounts/%s/balances', urlencode($accountId)),
            [],
            $psuHeaders,
        );
    }

    /**
     * @param array<string, mixed>  $options
     * @param array<string, string> $psuHeaders
     *
     * @return array<string, mixed>
     */
    private function request(string $method, string $path, array $options = [], array $psuHeaders = []): array
    {
        $response = $this->httpClient->request($method, $this->baseUrl.$path, array_merge($options, [
            'headers' => array_merge([
                'Authorization' => 'Bearer '.$this->createToken(),
                'Accept' => 'application/json',
            ], $psuHeaders),
        ]));

        $status = $response->getStatusCode();
        $body = $response->toArray(throw: false);

        if (429 === $status) {
            // The bank's own ceiling, not the provider's: most allow four
            // fetches a day without the user present. Retrying now would only
            // burn what is left, so say so and stop.
            throw new RateLimitedException(sprintf('The bank refused a further fetch for now (%s). Most banks allow four a day without the user present; try again in a few hours.', $body['error'] ?? 'rate limited'));
        }

        if ($status >= 400) {
            throw new \RuntimeException(sprintf('Enable Banking answered %d on %s: %s', $status, $path, $body['message'] ?? $body['error'] ?? 'no message'));
        }

        return $body;
    }

    /**
     * The JWT they expect: RS256, the application id as key id, and a short
     * life. Built by hand rather than pulling a JWT library for three claims.
     */
    private function createToken(): string
    {
        $issuedAt = time();

        $header = ['typ' => 'JWT', 'alg' => 'RS256', 'kid' => $this->applicationId];
        $claims = [
            'iss' => 'enablebanking.com',
            'aud' => 'api.enablebanking.com',
            'iat' => $issuedAt,
            'exp' => $issuedAt + self::TOKEN_TTL_SECONDS,
        ];

        $payload = $this->base64Url($header).'.'.$this->base64Url($claims);

        $key = openssl_pkey_get_private($this->readPrivateKey());
        if (false === $key) {
            throw new \RuntimeException('The Enable Banking private key could not be read.');
        }

        if (!openssl_sign($payload, $signature, $key, OPENSSL_ALGO_SHA256)) {
            throw new \RuntimeException('Could not sign the Enable Banking token.');
        }

        return $payload.'.'.$this->base64UrlEncode($signature);
    }

    private function readPrivateKey(): string
    {
        if (!is_readable($this->privateKeyPath)) {
            throw new \RuntimeException(sprintf('The Enable Banking private key is missing at "%s".', $this->privateKeyPath));
        }

        return (string) file_get_contents($this->privateKeyPath);
    }

    /** @param array<string, mixed> $data */
    private function base64Url(array $data): string
    {
        return $this->base64UrlEncode(json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES));
    }

    private function base64UrlEncode(string $raw): string
    {
        return rtrim(strtr(base64_encode($raw), '+/', '-_'), '=');
    }
}
