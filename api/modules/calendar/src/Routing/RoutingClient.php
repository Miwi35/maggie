<?php

declare(strict_types=1);

namespace Maggie\Calendar\Routing;

use Maggie\Calendar\Enum\TripMode;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;
use Symfony\Contracts\HttpClient\HttpClientInterface;

/**
 * Google Routes `computeRoutes` (MAG-653): one route between two points, by
 * car with the traffic, by transit, on foot or by bicycle. A car, foot or
 * bicycle route is a single leg; a transit one keeps Google's steps — the
 * walks between stops merged, each ride named by its line.
 */
class RoutingClient
{
    private const TIMEOUT_SECONDS = 8;

    private const FIELD_MASK = 'routes.duration,routes.legs.steps.travelMode,routes.legs.steps.staticDuration,'
        .'routes.legs.steps.transitDetails.transitLine.nameShort,routes.legs.steps.transitDetails.transitLine.name';

    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $routesUrl,
        private readonly string $googleMapsApiKey,
    ) {
    }

    /**
     * $departAt is only sent by car (the traffic of that hour), $arriveBy only
     * by transit (the timetable); a past instant is never sent, Google refuses it.
     *
     * @throws NoRouteException Google answered without a route
     * @throws RoutingException
     */
    public function route(
        TripMode $mode,
        float $fromLatitude,
        float $fromLongitude,
        float $toLatitude,
        float $toLongitude,
        ?\DateTimeImmutable $departAt = null,
        ?\DateTimeImmutable $arriveBy = null,
    ): Route {
        if ('' === trim($this->googleMapsApiKey)) {
            throw new RoutingException('No Google Maps API key is configured (GOOGLE_MAPS_API_KEY): trips cannot be estimated.');
        }

        $body = [
            'origin' => self::waypoint($fromLatitude, $fromLongitude),
            'destination' => self::waypoint($toLatitude, $toLongitude),
            'travelMode' => $mode->routesTravelMode(),
            'languageCode' => 'fr-FR',
            'units' => 'METRIC',
        ];
        if (TripMode::Drive === $mode) {
            $body['routingPreference'] = 'TRAFFIC_AWARE';
            if (null !== $departAt) {
                $body['departureTime'] = self::instant($departAt);
            }
        }
        if (TripMode::Transit === $mode && null !== $arriveBy) {
            $body['arrivalTime'] = self::instant($arriveBy);
        }

        try {
            $data = $this->httpClient->request('POST', $this->routesUrl, [
                'json' => $body,
                'headers' => ['X-Goog-Api-Key' => $this->googleMapsApiKey, 'X-Goog-FieldMask' => self::FIELD_MASK],
                'timeout' => self::TIMEOUT_SECONDS,
            ])->toArray();
        } catch (ExceptionInterface $e) {
            throw new RoutingException('Google Routes is unreachable or answered with an error.', 0, $e);
        }

        $route = $data['routes'][0] ?? null;
        if (!\is_array($route)) {
            throw new NoRouteException(sprintf('Google Routes found no %s route between these two points.', $mode->value));
        }
        // Google leaves the duration out of a route that goes nowhere: already there.
        $duration = self::seconds($route['duration'] ?? null) ?? 0;

        $legs = TripMode::Transit === $mode ? self::transitLegs($route) : [];

        return new Route($duration, [] !== $legs ? $legs : [new RouteLeg($mode, $duration)]);
    }

    /**
     * @param array<mixed> $route
     *
     * @return list<RouteLeg>
     */
    private static function transitLegs(array $route): array
    {
        $legs = [];
        foreach ($route['legs'] ?? [] as $leg) {
            foreach ((\is_array($leg) ? $leg['steps'] ?? [] : []) as $step) {
                if (!\is_array($step)) {
                    continue;
                }
                $seconds = self::seconds($step['staticDuration'] ?? null) ?? 0;

                if ('TRANSIT' === ($step['travelMode'] ?? null)) {
                    $line = $step['transitDetails']['transitLine'] ?? [];
                    $label = $line['nameShort'] ?? $line['name'] ?? null;
                    $legs[] = new RouteLeg(TripMode::Transit, $seconds, \is_string($label) ? $label : null);

                    continue;
                }

                // Google cuts a walk at every turn: one walk between two rides.
                $last = array_key_last($legs);
                if (null !== $last && TripMode::Walk === $legs[$last]->kind) {
                    $legs[$last] = new RouteLeg(TripMode::Walk, $legs[$last]->seconds + $seconds);
                } else {
                    $legs[] = new RouteLeg(TripMode::Walk, $seconds);
                }
            }
        }

        return $legs;
    }

    /** @return array{location: array{latLng: array{latitude: float, longitude: float}}} */
    private static function waypoint(float $latitude, float $longitude): array
    {
        return ['location' => ['latLng' => ['latitude' => $latitude, 'longitude' => $longitude]]];
    }

    private static function instant(\DateTimeImmutable $at): string
    {
        return $at->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d\TH:i:s\Z');
    }

    /** Google writes a duration as `"840s"`. */
    private static function seconds(mixed $duration): ?int
    {
        if (!\is_string($duration) || 1 !== preg_match('/^(\d+(?:\.\d+)?)s$/', $duration, $match)) {
            return null;
        }

        return (int) round((float) $match[1]);
    }
}
