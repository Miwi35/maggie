<?php

declare(strict_types=1);

namespace Maggie\Core\Weather;

use Symfony\Contracts\Cache\CacheInterface;
use Symfony\Contracts\Cache\ItemInterface;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Open-Meteo: free and keyless. A city name is turned into coordinates by the
 * geocoding API, then the forecast API gives the daily figures. Both answers
 * are cached — a place does not move, a forecast changes slowly — and a failure
 * is never cached.
 */
class OpenMeteoClient
{
    private const GEOCODING_TTL = 86400;
    private const FORECAST_TTL = 1800;
    private const TIMEOUT_SECONDS = 5;

    private const DAILY_FIELDS = [
        'weather_code',
        'temperature_2m_max',
        'temperature_2m_min',
        'precipitation_sum',
        'precipitation_probability_max',
        'wind_speed_10m_max',
        'wind_gusts_10m_max',
    ];

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly CacheInterface $cache,
        private readonly string $forecastUrl,
        private readonly string $geocodingUrl,
    ) {
    }

    /**
     * @return array{name: string, country: ?string, latitude: float, longitude: float}
     *
     * @throws LocationNotFoundException
     * @throws WeatherException
     */
    public function geocode(string $city): array
    {
        $key = 'weather.geocoding.'.sha1(mb_strtolower($city));

        return $this->cache->get($key, function (ItemInterface $item) use ($city): array {
            $item->expiresAfter(self::GEOCODING_TTL);

            $place = null;
            foreach ($this->nameCandidates($city) as $name) {
                $data = $this->fetch($this->geocodingUrl, [
                    'name' => $name,
                    'count' => 1,
                    'language' => 'fr',
                    'format' => 'json',
                ]);

                $place = $data['results'][0] ?? null;
                if (null !== $place) {
                    break;
                }
            }
            if (null === $place) {
                throw new LocationNotFoundException($city);
            }
            if (!isset($place['name'], $place['latitude'], $place['longitude'])) {
                throw new WeatherException('Open-Meteo returned a place without coordinates.');
            }

            return [
                'name' => (string) $place['name'],
                'country' => isset($place['country']) ? (string) $place['country'] : null,
                'latitude' => (float) $place['latitude'],
                'longitude' => (float) $place['longitude'],
            ];
        });
    }

    /**
     * The geocoder matches a bare place name: « Rennes 35000 » or « Rennes (35) » find nothing.
     * The text as written goes first, then the same text without brackets, digits and what follows a comma.
     *
     * @return list<string>
     */
    private function nameCandidates(string $city): array
    {
        $first = trim(explode(',', $city)[0]);
        $bare = trim((string) preg_replace('/\([^)]*\)|\d+/u', ' ', $first));
        $bare = trim((string) preg_replace('/\s+/u', ' ', $bare));

        return array_values(array_unique(array_filter([$city, $bare])));
    }

    /**
     * One row per day between $from and $to (Y-m-d, inclusive), in the
     * location's own timezone.
     *
     * @return list<array<string, mixed>>
     *
     * @throws WeatherException
     */
    public function forecast(float $latitude, float $longitude, string $from, string $to): array
    {
        $key = 'weather.forecast.'.sha1(sprintf('%.4f|%.4f|%s|%s', $latitude, $longitude, $from, $to));

        return $this->cache->get($key, function (ItemInterface $item) use ($latitude, $longitude, $from, $to): array {
            $item->expiresAfter(self::FORECAST_TTL);

            $data = $this->fetch($this->forecastUrl, [
                'latitude' => $latitude,
                'longitude' => $longitude,
                'daily' => implode(',', self::DAILY_FIELDS),
                'timezone' => 'auto',
                'start_date' => $from,
                'end_date' => $to,
            ]);

            $daily = $data['daily'] ?? null;
            if (!\is_array($daily) || !isset($daily['time']) || !\is_array($daily['time'])) {
                throw new WeatherException('Open-Meteo returned no daily forecast.');
            }

            $days = [];
            foreach ($daily['time'] as $index => $date) {
                $row = ['date' => (string) $date];
                foreach (self::DAILY_FIELDS as $field) {
                    $row[$field] = $daily[$field][$index] ?? null;
                }
                $days[] = $row;
            }

            return $days;
        });
    }

    /**
     * @param array<string, scalar> $query
     *
     * @return array<string, mixed>
     *
     * @throws WeatherException
     */
    private function fetch(string $url, array $query): array
    {
        try {
            return $this->httpClient
                ->request('GET', $url, ['query' => $query, 'timeout' => self::TIMEOUT_SECONDS])
                ->toArray();
        } catch (ExceptionInterface $e) {
            throw new WeatherException('Open-Meteo is unreachable or answered with an error.', 0, $e);
        }
    }
}
