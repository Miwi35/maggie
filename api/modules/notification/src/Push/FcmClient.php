<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Psr\Log\LoggerInterface;
use Symfony\Contracts\Cache\CacheInterface;
use Symfony\Contracts\Cache\ItemInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Sends one message through FCM HTTP v1.
 *
 * Authenticates as the GCP service account whose JSON key is mounted at
 * `FCM_CREDENTIALS_PATH` (k8s secret `fcm-credentials`): a JWT signed with its
 * key buys an hour-long access token, kept in cache. Built by hand, like the
 * Enable Banking client, rather than pulling Guzzle in through google/auth.
 *
 * No key file — dev, tests, e2e — and the client says it is not configured:
 * nothing is sent, nothing fails.
 */
class FcmClient
{
    private const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
    private const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';
    private const SEND_URL = 'https://fcm.googleapis.com/v1/projects/%s/messages:send';
    private const ASSERTION_TTL_SECONDS = 3600;
    /** Renewed this long before Google's expiry, so a token never dies mid-send. */
    private const EXPIRY_MARGIN_SECONDS = 300;

    /** @var array{client_email: string, private_key: string, token_uri: string, project_id: string}|null */
    private ?array $credentials = null;

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly CacheInterface $cache,
        private readonly LoggerInterface $logger,
        private readonly string $credentialsPath = '',
        private readonly string $projectId = '',
    ) {
    }

    public function isConfigured(): bool
    {
        return '' !== $this->credentialsPath && is_readable($this->credentialsPath);
    }

    /**
     * @param array<string, mixed> $message the FCM `message` object, `token` included
     *
     * @throws FcmUnavailableException when trying again later may work
     */
    public function send(array $message): FcmSendResult
    {
        $credentials = $this->credentials();
        $projectId = '' !== $this->projectId ? $this->projectId : $credentials['project_id'];

        try {
            $response = $this->httpClient->request('POST', sprintf(self::SEND_URL, rawurlencode($projectId)), [
                'auth_bearer' => $this->accessToken($credentials),
                'json' => ['message' => $message],
            ]);
            $status = $response->getStatusCode();
            $body = $response->toArray(false);
        } catch (\Symfony\Contracts\HttpClient\Exception\ExceptionInterface $e) {
            throw new FcmUnavailableException('FCM could not be reached: '.$e->getMessage(), 0, $e);
        }

        if ($status < 300) {
            return FcmSendResult::Sent;
        }

        $error = \is_array($body['error'] ?? null) ? $body['error'] : [];
        $errorCode = $this->errorCode($error);
        $detail = sprintf('FCM answered %d (%s): %s', $status, $errorCode ?? 'no code', $error['message'] ?? 'no message');

        // A token that is not one at all comes back as a plain INVALID_ARGUMENT;
        // only its message tells it from a malformed payload.
        $malformedToken = 400 === $status && str_contains((string) ($error['message'] ?? ''), 'registration token');

        // Only FCM's own verdict on the token: a bare 404 (a wrong project id)
        // would otherwise wipe every device. SENDER_ID_MISMATCH is a token
        // issued for another Firebase project — never deliverable from here.
        if (\in_array($errorCode, ['UNREGISTERED', 'SENDER_ID_MISMATCH'], true) || $malformedToken) {
            return FcmSendResult::UnknownToken;
        }

        if (401 === $status || 403 === $status) {
            // A revoked key or a token Google no longer honours: get a new one next time.
            $this->cache->delete($this->cacheKey($credentials));

            throw new FcmUnavailableException($detail);
        }

        if (429 === $status || $status >= 500) {
            throw new FcmUnavailableException($detail);
        }

        $this->logger->error('FCM refused a push: {detail}', ['detail' => $detail]);

        return FcmSendResult::Rejected;
    }

    /** @param array{client_email: string, private_key: string, token_uri: string, project_id: string} $credentials */
    private function accessToken(array $credentials): string
    {
        return $this->cache->get($this->cacheKey($credentials), function (ItemInterface $item) use ($credentials): string {
            $response = $this->httpClient->request('POST', $credentials['token_uri'], [
                'body' => [
                    'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                    'assertion' => $this->assertion($credentials),
                ],
            ]);

            $status = $response->getStatusCode();
            $body = $response->toArray(false);
            $token = $body['access_token'] ?? null;

            if ($status >= 300 || !\is_string($token)) {
                throw new FcmUnavailableException(sprintf('Google refused the FCM service account (%d): %s', $status, $body['error_description'] ?? $body['error'] ?? 'no message'));
            }

            $expiresIn = (int) ($body['expires_in'] ?? self::ASSERTION_TTL_SECONDS);
            $item->expiresAfter(max(60, $expiresIn - self::EXPIRY_MARGIN_SECONDS));

            return $token;
        });
    }

    /** @param array{client_email: string, private_key: string, token_uri: string, project_id: string} $credentials */
    private function assertion(array $credentials): string
    {
        $issuedAt = time();
        $header = ['alg' => 'RS256', 'typ' => 'JWT'];
        $claims = [
            'iss' => $credentials['client_email'],
            'scope' => self::SCOPE,
            'aud' => $credentials['token_uri'],
            'iat' => $issuedAt,
            'exp' => $issuedAt + self::ASSERTION_TTL_SECONDS,
        ];

        $payload = $this->base64Url($header).'.'.$this->base64Url($claims);

        $key = openssl_pkey_get_private($credentials['private_key']);
        if (false === $key || !openssl_sign($payload, $signature, $key, OPENSSL_ALGO_SHA256)) {
            throw new \RuntimeException('The FCM service account key could not sign a token.');
        }

        return $payload.'.'.$this->base64UrlEncode($signature);
    }

    /** @return array{client_email: string, private_key: string, token_uri: string, project_id: string} */
    private function credentials(): array
    {
        if (null !== $this->credentials) {
            return $this->credentials;
        }

        if (!$this->isConfigured()) {
            throw new \LogicException(sprintf('No FCM service account key at "%s".', $this->credentialsPath));
        }

        $json = json_decode((string) file_get_contents($this->credentialsPath), true);
        if (!\is_array($json) || !\is_string($json['client_email'] ?? null) || !\is_string($json['private_key'] ?? null)) {
            throw new \RuntimeException(sprintf('"%s" is not a service account key: client_email and private_key are required.', $this->credentialsPath));
        }

        return $this->credentials = [
            'client_email' => $json['client_email'],
            'private_key' => $json['private_key'],
            'token_uri' => \is_string($json['token_uri'] ?? null) ? $json['token_uri'] : self::DEFAULT_TOKEN_URI,
            'project_id' => \is_string($json['project_id'] ?? null) ? $json['project_id'] : '',
        ];
    }

    /** @param array{client_email: string, private_key: string, token_uri: string, project_id: string} $credentials */
    private function cacheKey(array $credentials): string
    {
        return 'fcm_access_token_'.hash('xxh128', $credentials['client_email']);
    }

    /**
     * FCM's own code (`UNREGISTERED`, `QUOTA_EXCEEDED`…) sits in the details,
     * the top-level status is the generic gRPC one.
     *
     * @param array<string, mixed> $error
     */
    private function errorCode(array $error): ?string
    {
        foreach (\is_array($error['details'] ?? null) ? $error['details'] : [] as $detail) {
            if (\is_array($detail) && \is_string($detail['errorCode'] ?? null)) {
                return $detail['errorCode'];
            }
        }

        return \is_string($error['status'] ?? null) ? $error['status'] : null;
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
