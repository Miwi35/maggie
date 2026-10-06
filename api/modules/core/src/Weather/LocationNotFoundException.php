<?php

declare(strict_types=1);

namespace Maggie\Core\Weather;

final class LocationNotFoundException extends WeatherException
{
    public function __construct(public readonly string $location)
    {
        parent::__construct(sprintf('No place found for "%s".', $location));
    }
}
