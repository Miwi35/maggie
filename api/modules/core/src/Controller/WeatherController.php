<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Maggie\Core\Entity\User;
use Maggie\Core\Enum\WeatherCondition;
use Maggie\Core\Mcp\Tool\GetWeatherTool;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\Weather\LocationNotFoundException;
use Maggie\Core\Weather\OpenMeteoClient;
use Maggie\Core\Weather\WeatherAlerts;
use Maggie\Core\Weather\WeatherException;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Clock\Clock;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The day's weather for the fronts (MAG-710): same service and same city as the
 * get_weather MCP tool, one day, with the condition grouped from the WMO code.
 */
final class WeatherController
{
    private const MAX_DAYS_AHEAD = 15;

    public function __construct(
        private readonly Security $security,
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly OpenMeteoClient $client,
    ) {
    }

    #[Route('/api/weather', name: 'api_weather', methods: ['GET'])]
    public function __invoke(Request $request): Response
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $preference = $this->preferenceRepository->findOneByUser($user);
        try {
            $timezone = new \DateTimeZone($preference?->getTimezone() ?? GetWeatherTool::DEFAULT_TIMEZONE);
        } catch (\Exception) {
            $timezone = new \DateTimeZone(GetWeatherTool::DEFAULT_TIMEZONE);
        }

        $today = Clock::get()->now()->setTimezone($timezone)->setTime(0, 0);
        $raw = $request->query->get('date');
        $date = \is_string($raw) ? \DateTimeImmutable::createFromFormat('!Y-m-d', $raw, $timezone) : false;
        if (false === $date || $date->format('Y-m-d') !== $raw) {
            return new JsonResponse(['error' => 'date must be a valid YYYY-MM-DD', 'field' => 'date'], Response::HTTP_BAD_REQUEST);
        }
        if ($date < $today || $date > $today->modify(sprintf('+%d days', self::MAX_DAYS_AHEAD))) {
            return new JsonResponse(['error' => sprintf('date must be between today and %d days ahead', self::MAX_DAYS_AHEAD), 'field' => 'date'], Response::HTTP_BAD_REQUEST);
        }

        $city = trim($preference?->getDefaultCity() ?? '');
        if ('' === $city) {
            return new JsonResponse(['error' => 'Aucune ville'], Response::HTTP_NOT_FOUND);
        }

        try {
            $place = $this->client->geocode($city);
            $rows = $this->client->forecast($place['latitude'], $place['longitude'], $raw, $raw);
        } catch (LocationNotFoundException) {
            return new JsonResponse(['error' => 'Aucune ville'], Response::HTTP_NOT_FOUND);
        } catch (WeatherException) {
            return new JsonResponse(['error' => 'Weather service unavailable'], Response::HTTP_SERVICE_UNAVAILABLE);
        }

        $row = $rows[0] ?? null;
        if (null === $row) {
            return new JsonResponse(['error' => 'Weather service unavailable'], Response::HTTP_SERVICE_UNAVAILABLE);
        }
        $code = isset($row['weather_code']) ? (int) $row['weather_code'] : null;

        return new JsonResponse([
            'date' => $row['date'],
            'minC' => $row['temperature_2m_min'],
            'maxC' => $row['temperature_2m_max'],
            'rainMm' => $row['precipitation_sum'],
            'snowCm' => $row['snowfall_sum'] ?? null,
            'rainProbability' => $row['precipitation_probability_max'],
            'windKmh' => $row['wind_speed_10m_max'],
            'alerts' => WeatherAlerts::forDay($row, $code),
            'condition' => null === $code ? null : WeatherCondition::fromWmo($code)?->value,
        ]);
    }
}
