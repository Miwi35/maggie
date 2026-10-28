<?php

declare(strict_types=1);

namespace Maggie\Core\Location;

/** One GPS position the phone reported (MAG-666). */
final readonly class LocationFix
{
    public function __construct(
        public float $latitude,
        public float $longitude,
        /** Radius in metres the phone vouches for. */
        public float $accuracy,
        public \DateTimeImmutable $capturedAt,
    ) {
    }

    /** @return array{latitude: float, longitude: float, accuracy: float, capturedAt: string, ageMinutes: int} */
    public function toArray(\DateTimeImmutable $now): array
    {
        return [
            'latitude' => $this->latitude,
            'longitude' => $this->longitude,
            'accuracy' => $this->accuracy,
            'capturedAt' => $this->capturedAt->format(\DATE_ATOM),
            'ageMinutes' => max(0, intdiv($now->getTimestamp() - $this->capturedAt->getTimestamp(), 60)),
        ];
    }
}
