<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Support;

use Maggie\Calendar\Routing\GeocodingClient;
use Maggie\Calendar\Routing\RoutingClient;
use Maggie\Core\Entity\User;
use Maggie\Notification\Push\LocationRequester;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/**
 * Google Routes and Geocoding simulated for a kernel test: every route lasts `$routeSeconds`, or
 * fails while `$routingDown`; every address is in Rennes; the phones are never pushed.
 */
trait FakeRoutingTrait
{
    private int $routeSeconds = 600;
    private bool $routingDown = false;

    private function fakeRouting(): void
    {
        $http = new MockHttpClient(function (string $method, string $url): ResponseInterface {
            $json = static fn (array $body, int $status = 200): MockResponse => new MockResponse(json_encode($body, JSON_THROW_ON_ERROR), [
                'http_code' => $status,
                'response_headers' => ['content-type: application/json'],
            ]);

            if (str_contains($url, 'geocode')) {
                return $json(['status' => 'OK', 'results' => [['geometry' => ['location' => ['lat' => 48.118, 'lng' => -1.668]]]]]);
            }
            if ($this->routingDown) {
                return $json(['error' => 'down'], 503);
            }

            return $json(['routes' => [['duration' => $this->routeSeconds.'s']]]);
        });

        $container = self::getContainer();
        $container->set(GeocodingClient::class, new GeocodingClient($http, 'http://geocoding.test/maps/api/geocode/json', 'test-key'));
        $container->set(RoutingClient::class, new RoutingClient($http, 'http://routes.test/directions/v2:computeRoutes', 'test-key'));
        $container->set(LocationRequester::class, new class extends LocationRequester {
            public function __construct()
            {
            }

            public function request(User $user): int
            {
                return 1;
            }
        });
    }
}
