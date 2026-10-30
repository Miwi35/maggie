<?php

declare(strict_types=1);

namespace Maggie\Core\Enum;

/** The day's weather in nine words, grouped from Open-Meteo's WMO `weather_code` (MAG-710). */
enum WeatherCondition: string
{
    case Clear = 'clear';
    case PartlyCloudy = 'partly_cloudy';
    case Cloudy = 'cloudy';
    case Fog = 'fog';
    case Drizzle = 'drizzle';
    case Rain = 'rain';
    case Showers = 'showers';
    case Snow = 'snow';
    case Storm = 'storm';

    public static function fromWmo(int $code): ?self
    {
        return match (true) {
            0 === $code || 1 === $code => self::Clear,
            2 === $code => self::PartlyCloudy,
            3 === $code => self::Cloudy,
            45 === $code || 48 === $code => self::Fog,
            $code >= 51 && $code <= 57 => self::Drizzle,
            $code >= 61 && $code <= 67 => self::Rain,
            $code >= 71 && $code <= 77 => self::Snow,
            $code >= 80 && $code <= 82 => self::Showers,
            85 === $code || 86 === $code => self::Snow,
            $code >= 95 && $code <= 99 => self::Storm,
            default => null,
        };
    }
}
