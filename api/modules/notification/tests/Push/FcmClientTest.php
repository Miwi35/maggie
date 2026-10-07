<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Push;

use Maggie\Notification\Push\FcmClient;
use Maggie\Notification\Push\FcmSendResult;
use Maggie\Notification\Push\FcmUnavailableException;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * FCM HTTP v1 with a service account key: an OAuth token bought with a signed
 * JWT, then one POST per device. Google is mocked; the key is a throwaway one.
 */
final class FcmClientTest extends TestCase
{
    private string $keyPath;
    private string $publicKey;

    protected function setUp(): void
    {
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        $this->publicKey = openssl_pkey_get_details($key)['key'];

        $this->keyPath = (string) tempnam(sys_get_temp_dir(), 'fcm-key-');
        file_put_contents($this->keyPath, json_encode([
            'type' => 'service_account',
            'project_id' => 'project-from-key',
            'client_email' => 'push@project.iam.gserviceaccount.com',
            'private_key' => $pem,
            'token_uri' => 'https://oauth2.example.test/token',
        ], JSON_THROW_ON_ERROR));
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
    }

    public function testWithoutAKeyItIsNotConfigured(): void
    {
        self::assertFalse((new FcmClient(new MockHttpClient(), new ArrayAdapter(), new NullLogger(), ''))->isConfigured());
        self::assertFalse((new FcmClient(new MockHttpClient(), new ArrayAdapter(), new NullLogger(), '/nowhere/key.json'))->isConfigured());
        self::assertTrue($this->client(new MockHttpClient())->isConfigured());
    }

    public function testItBuysATokenWithASignedJwtThenSends(): void
    {
        $requests = [];
        $http = new MockHttpClient(function (string $method, string $url, array $options) use (&$requests) {
            $requests[] = [$method, $url, $options];

            return str_contains($url, 'oauth2')
                ? new MockResponse(json_encode(['access_token' => 'ya29.token', 'expires_in' => 3599]))
                : new MockResponse(json_encode(['name' => 'projects/maggie-487318/messages/1']));
        });

        $result = $this->client($http, 'maggie-487318')->send(['token' => 'device', 'notification' => ['title' => 'Coucou']]);

        self::assertSame(FcmSendResult::Sent, $result);
        self::assertCount(2, $requests);

        [$method, $url, $options] = $requests[0];
        self::assertSame(['POST', 'https://oauth2.example.test/token'], [$method, $url]);
        parse_str($options['body'], $form);
        self::assertSame('urn:ietf:params:oauth:grant-type:jwt-bearer', $form['grant_type']);
        $claims = $this->verifiedClaims($form['assertion']);
        self::assertSame('push@project.iam.gserviceaccount.com', $claims['iss']);
        self::assertSame('https://www.googleapis.com/auth/firebase.messaging', $claims['scope']);
        self::assertSame('https://oauth2.example.test/token', $claims['aud']);

        [$method, $url, $options] = $requests[1];
        self::assertSame(['POST', 'https://fcm.googleapis.com/v1/projects/maggie-487318/messages:send'], [$method, $url]);
        self::assertContains('Authorization: Bearer ya29.token', $options['headers']);
        self::assertSame(['message' => ['token' => 'device', 'notification' => ['title' => 'Coucou']]], json_decode($options['body'], true));
    }

    public function testTheProjectDefaultsToTheKeysOwn(): void
    {
        $urls = [];
        $http = new MockHttpClient(function (string $method, string $url) use (&$urls) {
            $urls[] = $url;

            return new MockResponse(json_encode(['access_token' => 'tok', 'expires_in' => 3599]));
        });

        $this->client($http)->send(['token' => 'device']);

        self::assertSame('https://fcm.googleapis.com/v1/projects/project-from-key/messages:send', $urls[1]);
    }

    public function testTheAccessTokenIsReusedUntilItExpires(): void
    {
        $oauthCalls = 0;
        $http = new MockHttpClient(function (string $method, string $url) use (&$oauthCalls) {
            if (str_contains($url, 'oauth2')) {
                ++$oauthCalls;
            }

            return new MockResponse(json_encode(['access_token' => 'tok', 'expires_in' => 3599]));
        });
        $client = $this->client($http);

        $client->send(['token' => 'a']);
        $client->send(['token' => 'b']);

        self::assertSame(1, $oauthCalls);
    }

    public function testAnUnregisteredTokenIsReportedAsUnknown(): void
    {
        $result = $this->client($this->fcmAnswering(404, [
            'error' => ['code' => 404, 'status' => 'NOT_FOUND', 'message' => 'Requested entity was not found.', 'details' => [['errorCode' => 'UNREGISTERED']]],
        ]))->send(['token' => 'gone']);

        self::assertSame(FcmSendResult::UnknownToken, $result);
    }

    public function testAMalformedTokenIsReportedAsUnknown(): void
    {
        $result = $this->client($this->fcmAnswering(400, [
            'error' => ['code' => 400, 'status' => 'INVALID_ARGUMENT', 'message' => 'The registration token is not a valid FCM registration token'],
        ]))->send(['token' => 'garbage']);

        self::assertSame(FcmSendResult::UnknownToken, $result);
    }

    public function testAMessageGoogleRefusesIsRejectedWithoutRetry(): void
    {
        $result = $this->client($this->fcmAnswering(400, [
            'error' => ['code' => 400, 'status' => 'INVALID_ARGUMENT', 'message' => 'Invalid value at \'message.data\''],
        ]))->send(['token' => 'device']);

        self::assertSame(FcmSendResult::Rejected, $result);
    }

    public function testAnOutageIsWorthRetrying(): void
    {
        $this->expectException(FcmUnavailableException::class);

        $this->client($this->fcmAnswering(503, ['error' => ['code' => 503, 'status' => 'UNAVAILABLE']]))->send(['token' => 'device']);
    }

    public function testARefusedAccessTokenIsDroppedAndBoughtAgain(): void
    {
        $oauthCalls = 0;
        $sends = 0;
        $http = new MockHttpClient(function (string $method, string $url) use (&$oauthCalls, &$sends) {
            if (str_contains($url, 'oauth2')) {
                ++$oauthCalls;

                return new MockResponse(json_encode(['access_token' => 'tok'.$oauthCalls, 'expires_in' => 3599]));
            }

            return 1 === ++$sends
                ? new MockResponse(json_encode(['error' => ['code' => 401, 'status' => 'UNAUTHENTICATED']]), ['http_code' => 401])
                : new MockResponse('{}');
        });
        $client = $this->client($http);

        try {
            $client->send(['token' => 'device']);
            self::fail('A 401 must be retried.');
        } catch (FcmUnavailableException) {
        }

        self::assertSame(FcmSendResult::Sent, $client->send(['token' => 'device']));
        self::assertSame(2, $oauthCalls);
    }

    public function testAServiceAccountGoogleRefusesIsWorthRetrying(): void
    {
        $http = new MockHttpClient(new MockResponse(json_encode(['error' => 'invalid_grant', 'error_description' => 'Invalid JWT Signature.']), ['http_code' => 400]));

        $this->expectException(FcmUnavailableException::class);
        $this->expectExceptionMessage('Invalid JWT Signature.');

        $this->client($http)->send(['token' => 'device']);
    }

    private function client(MockHttpClient $http, string $projectId = ''): FcmClient
    {
        return new FcmClient($http, new ArrayAdapter(), new NullLogger(), $this->keyPath, $projectId);
    }

    /** @param array<string, mixed> $body */
    private function fcmAnswering(int $status, array $body): MockHttpClient
    {
        return new MockHttpClient(static fn (string $method, string $url) => str_contains($url, 'oauth2')
            ? new MockResponse(json_encode(['access_token' => 'tok', 'expires_in' => 3599]))
            : new MockResponse(json_encode($body), ['http_code' => $status]));
    }

    /** @return array<string, mixed> */
    private function verifiedClaims(string $jwt): array
    {
        [$header, $claims, $signature] = explode('.', $jwt);
        $decode = static fn (string $part) => base64_decode(strtr($part, '-_', '+/'), true);

        self::assertSame(['alg' => 'RS256', 'typ' => 'JWT'], json_decode($decode($header), true));
        self::assertSame(1, openssl_verify($header.'.'.$claims, $decode($signature), $this->publicKey, OPENSSL_ALGO_SHA256));

        return json_decode($decode($claims), true);
    }
}
