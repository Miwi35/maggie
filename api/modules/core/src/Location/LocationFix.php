<?php

declare(strict_types=1);

namespace Maggie\Core\Location;

use Maggie\Core\Enum\LocationSource;

/** One position the phone reported, with the source it came from (MAG-666). */
final readonly class LocationFix
{
    public function __construct(
        public float $latitude,
        public float $longitude,
        /** Radius in metres the phone vouches for. */
        public float $accuracy,
        public \DateTimeImmutable $capturedAt,
        public LocationSource $source,
    ) {
    }

    /** @return array{source: string, latitude: float, longitude: float, accuracy: float, capturedAt: string, ageMinutes: int} */
    public function toArray(\DateTimeImmutable $now): array
    {
        return [
            'source' => $this->source->value,
            'latitude' => $this->latitude,
            'longitude' => $this->longitude,
            'accuracy' => $this->accuracy,
            'capturedAt' => $this->capturedAt->format(\DATE_ATOM),
            'ageMinutes' => max(0, intdiv($now->getTimestamp() - $this->capturedAt->getTimestamp(), 60)),
        ];
    }
}
