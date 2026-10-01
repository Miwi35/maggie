<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Elastic\Elasticsearch\Client;
use Elastic\Elasticsearch\ClientBuilder;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Psr18Client;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * A real Elasticsearch client whose transport is a stub: the tests see the
 * exact requests the application would send, and answer them as the server
 * would — Client is final and cannot be doubled.
 */
trait RecordingElasticsearchTrait
{
    /** @var list<array{method: string, path: string, body: string}> */
    private array $requests = [];

    /**
     * @param \Closure(string, string): array{int, array<string, mixed>} $respond (method, path) → [status, JSON body]
     */
    private function recordingClient(\Closure $respond): Client
    {
        $http = new MockHttpClient(function (string $method, string $url, array $options) use ($respond): MockResponse {
            $path = (string) parse_url($url, \PHP_URL_PATH);
            $this->requests[] = ['method' => $method, 'path' => $path, 'body' => (string) ($options['body'] ?? '')];

            [$status, $body] = $respond($method, $path);

            return new MockResponse((string) json_encode($body), [
                'http_code' => $status,
                'response_headers' => ['X-Elastic-Product: Elasticsearch', 'Content-Type: application/json'],
            ]);
        });

        return ClientBuilder::create()
            ->setHosts(['http://elasticsearch.test:9200'])
            ->setHttpClient(new Psr18Client($http))
            ->build();
    }

    /**
     * @return array<string, mixed>
     */
    private function lastRequestBody(): array
    {
        $last = $this->requests[array_key_last($this->requests)] ?? self::fail('No request reached Elasticsearch.');
        $decoded = json_decode($last['body'], true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($decoded);

        return $decoded;
    }
}
