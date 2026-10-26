<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Google Geocoding (MAG-653): an address turned into coordinates. Nothing is
 * cached here — the callers store the answer on the event or the place.
 */
class GeocodingClient
{
    private const TIMEOUT_SECONDS = 5;

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $geocodingUrl,
        private readonly string $googleMapsApiKey,
    ) {
    }

    /**
     * @return array{latitude: float, longitude: float}
     *
     * @throws AddressNotFoundException
     * @throws RoutingException
     */
    public function geocode(string $address): array
    {
        if ('' === trim($this->googleMapsApiKey)) {
            throw new RoutingException('No Google Maps API key is configured (GOOGLE_MAPS_API_KEY): places cannot be located.');
        }

        try {
            $data = $this->httpClient->request('GET', $this->geocodingUrl, [
                'query' => ['address' => $address, 'language' => 'fr', 'region' => 'fr', 'key' => $this->googleMapsApiKey],
                'timeout' => self::TIMEOUT_SECONDS,
            ])->toArray();
        } catch (ExceptionInterface $e) {
            throw new RoutingException('Google Geocoding is unreachable or answered with an error.', 0, $e);
        }

        $status = $data['status'] ?? null;
        if ('ZERO_RESULTS' === $status) {
            throw new AddressNotFoundException($address);
        }
        if ('OK' !== $status) {
            $detail = isset($data['error_message']) ? ': '.$data['error_message'] : '';

            throw new RoutingException(sprintf('Google Geocoding answered %s%s.', \is_string($status) ? $status : 'nothing', $detail));
        }

        $location = $data['results'][0]['geometry']['location'] ?? null;
        if (!\is_array($location) || !is_numeric($location['lat'] ?? null) || !is_numeric($location['lng'] ?? null)) {
            throw new RoutingException('Google Geocoding returned a place without coordinates.');
        }

        return ['latitude' => (float) $location['lat'], 'longitude' => (float) $location['lng']];
    }
}
