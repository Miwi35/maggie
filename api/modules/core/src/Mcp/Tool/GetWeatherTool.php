<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\Weather\LocationNotFoundException;
use Maggie\Core\Weather\OpenMeteoClient;
use Maggie\Core\Weather\WeatherException;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(
    name: 'get_weather',
    description: 'Daily weather forecast from Open-Meteo: min and max temperature (°C), rain (mm and probability), wind and gusts (km/h), and alerts. Without `date` it returns today and tomorrow; with `date` (YYYY-MM-DD, from today up to 15 days ahead) only that day. `location` is a city name; without it the city the user chose in their preferences is used, and if there is none the tool says so — ask the user for a city. Alerts are derived from the forecast (storm, strong wind, heavy rain, snow, heat, hard frost), not official warnings. When the weather service is down the result is an `error`: say so plainly and carry on without the weather.',
)]
class GetWeatherTool
{
    public const DEFAULT_TIMEZONE = 'Europe/Paris';
    private const MAX_DAYS_AHEAD = 15;

    private const CONDITIONS = [
        0 => 'ciel dégagé',
        1 => 'plutôt dégagé',
        2 => 'partiellement nuageux',
        3 => 'couvert',
        45 => 'brouillard',
        48 => 'brouillard givrant',
        51 => 'bruine légère',
        53 => 'bruine',
        55 => 'bruine dense',
        56 => 'bruine verglaçante',
        57 => 'bruine verglaçante dense',
        61 => 'pluie faible',
        63 => 'pluie',
        65 => 'pluie forte',
        66 => 'pluie verglaçante',
        67 => 'pluie verglaçante forte',
        71 => 'neige faible',
        73 => 'neige',
        75 => 'neige forte',
        77 => 'grains de neige',
        80 => 'averses faibles',
        81 => 'averses',
        82 => 'averses violentes',
        85 => 'averses de neige',
        86 => 'fortes averses de neige',
        95 => 'orage',
        96 => 'orage avec grêle',
        99 => 'orage avec forte grêle',
    ];

    public function __construct(
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly McpUserContext $userContext,
        private readonly OpenMeteoClient $client,
    ) {
    }

    public function __invoke(?string $date = null, ?string $location = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return $this->encode(['error' => $e->getMessage()]);
        }

        $preference = $this->preferenceRepository->findOneByUser($user);

        try {
            $timezone = new \DateTimeZone($preference?->getTimezone() ?? self::DEFAULT_TIMEZONE);
        } catch (\Exception) {
            // A stored value that is not a timezone must not take the tool down.
            $timezone = new \DateTimeZone(self::DEFAULT_TIMEZONE);
        }
        $today = new \DateTimeImmutable('today', $timezone);

        if (null === $date || '' === trim($date)) {
            $from = $today;
            $to = $today->modify('+1 day');
        } else {
            $from = $to = \DateTimeImmutable::createFromFormat('!Y-m-d', trim($date), $timezone) ?: null;
            if (null === $from || $from->format('Y-m-d') !== trim($date)) {
                return $this->encode(['error' => 'Invalid date: expected YYYY-MM-DD.']);
            }
            if ($from < $today || $from > $today->modify(sprintf('+%d days', self::MAX_DAYS_AHEAD))) {
                return $this->encode([
                    'error' => sprintf(
                        'Date out of range: the forecast covers %s to %s.',
                        $today->format('Y-m-d'),
                        $today->modify(sprintf('+%d days', self::MAX_DAYS_AHEAD))->format('Y-m-d'),
                    ),
                ]);
            }
        }

        $city = trim($location ?? '');
        if ('' === $city) {
            $city = trim($preference?->getDefaultCity() ?? '');
        }
        if ('' === $city) {
            return $this->encode([
                'error' => 'No location: give a city, or set a default city in the preferences.',
            ]);
        }

        try {
            $place = $this->client->geocode($city);
            $rows = $this->client->forecast(
                $place['latitude'],
                $place['longitude'],
                $from->format('Y-m-d'),
                $to->format('Y-m-d'),
            );
        } catch (LocationNotFoundException $e) {
            return $this->encode(['error' => $e->getMessage()]);
        } catch (WeatherException) {
            return $this->encode([
                'error' => 'The weather service (Open-Meteo) is unavailable right now. Try again later.',
            ]);
        }

        return $this->encode([
            'location' => ['name' => $place['name'], 'country' => $place['country']],
            'days' => array_map($this->describeDay(...), $rows),
        ]);
    }

    /**
     * @param array<string, mixed> $row
     *
     * @return array<string, mixed>
     */
    private function describeDay(array $row): array
    {
        $code = isset($row['weather_code']) ? (int) $row['weather_code'] : null;

        return [
            'date' => $row['date'],
            'condition' => null === $code ? null : (self::CONDITIONS[$code] ?? 'inconnu'),
            'temperature_min_c' => $row['temperature_2m_min'],
            'temperature_max_c' => $row['temperature_2m_max'],
            'rain_mm' => $row['precipitation_sum'],
            'rain_probability_percent' => $row['precipitation_probability_max'],
            'wind_max_kmh' => $row['wind_speed_10m_max'],
            'wind_gusts_max_kmh' => $row['wind_gusts_10m_max'],
            'alerts' => $this->alerts($row, $code),
        ];
    }

    /**
     * @param array<string, mixed> $row
     *
     * @return list<string>
     */
    private function alerts(array $row, ?int $code): array
    {
        $alerts = [];

        if (null !== $code && $code >= 95) {
            $alerts[] = 'orage';
        }
        if (null !== $code && \in_array($code, [71, 73, 75, 77, 85, 86], true)) {
            $alerts[] = 'neige';
        }
        $gusts = $row['wind_gusts_10m_max'] ?? null;
        if (is_numeric($gusts) && $gusts >= 70) {
            $alerts[] = 'vent violent';
        } elseif (is_numeric($gusts) && $gusts >= 50) {
            $alerts[] = 'vent fort';
        }
        $rain = $row['precipitation_sum'] ?? null;
        if (is_numeric($rain) && $rain >= 20) {
            $alerts[] = 'fortes pluies';
        }
        $max = $row['temperature_2m_max'] ?? null;
        if (is_numeric($max) && $max >= 35) {
            $alerts[] = 'forte chaleur';
        }
        $min = $row['temperature_2m_min'] ?? null;
        if (is_numeric($min) && $min <= -5) {
            $alerts[] = 'gel marqué';
        }

        return $alerts;
    }

    /** @param array<string, mixed> $data */
    private function encode(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE);
    }
}
