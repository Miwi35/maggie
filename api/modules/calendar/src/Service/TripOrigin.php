<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Core\Enum\LocationSource;

/**
 * Where a trip starts (MAG-653), resolved from `position`, `place:<ulid>` or
 * `event:<ulid>`. `locationRequested` tells the position was too old and the
 * phones were asked for a new one: the trip is computed on the old one.
 */
final readonly class TripOrigin
{
    public const string POSITION = 'position';
    public const string PLACE = 'place';
    public const string EVENT = 'event';

    public function __construct(
        public string $kind,
        public ?string $id,
        public string $label,
        public float $latitude,
        public float $longitude,
        public ?\DateTimeImmutable $capturedAt = null,
        public bool $locationRequested = false,
        /** For a position: how the phone obtained it. */
        public ?LocationSource $source = null,
    ) {
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        $origin = [
            'kind' => $this->kind,
            'id' => $this->id,
            'label' => $this->label,
            'latitude' => $this->latitude,
            'longitude' => $this->longitude,
        ];
        if (null !== $this->capturedAt) {
            $origin['capturedAt'] = $this->capturedAt->format(\DATE_ATOM);
        }
        if (null !== $this->source) {
            $origin['source'] = $this->source->value;
        }

        return $origin;
    }
}
