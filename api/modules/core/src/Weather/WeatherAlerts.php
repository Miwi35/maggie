<?php

declare(strict_types=1);

namespace Maggie\Core\Weather;

/** Alerts derived from a daily forecast row (not official warnings), shared by the MCP tool and the REST read. */
final class WeatherAlerts
{
    /**
     * @param array<string, mixed> $row
     *
     * @return list<string>
     */
    public static function forDay(array $row, ?int $code): array
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
}
