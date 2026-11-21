<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Enum\TripCarAt;
use Maggie\Calendar\Enum\TripLeg;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Routing\RouteLeg;

/**
 * One way to go to an event, door to door (MAG-653) — the contract `set_trip`,
 * the agent and the screens of « Partir à l'heure » read as {@see self::toArray()}.
 */
final readonly class TripEstimate
{
    /**
     * @param list<RouteLeg> $steps
     * @param TripCarAt|null $carAt where the user said the car is, as sent; not part of {@see self::toArray()}
     */
    public function __construct(
        public TripMode $mode,
        public TripOrigin $origin,
        public array $steps,
        public int $durationMinutes,
        public \DateTimeImmutable $departAt,
        public string $navigationUrl,
        public bool $parkingKnown,
        public ?float $carLatitude = null,
        public ?float $carLongitude = null,
        public ?\DateTimeImmutable $arriveAt = null,
        public TripLeg $leg = TripLeg::Outbound,
        public ?TripCarAt $carAt = null,
    ) {
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        $estimate = [
            'leg' => $this->leg->value,
            'mode' => $this->mode->value,
            'origin' => $this->origin->toArray(),
            'steps' => array_map(static fn (RouteLeg $step): array => array_filter([
                'kind' => $step->kind->value,
                'minutes' => TripPlanner::minutes($step->seconds),
                'label' => $step->label,
            ], static fn (mixed $value): bool => null !== $value), $this->steps),
            'durationMinutes' => $this->durationMinutes,
            'departAt' => $this->departAt->format(\DATE_ATOM),
            'navigationUrl' => $this->navigationUrl,
            'parkingKnown' => $this->parkingKnown,
        ];
        if (null !== $this->arriveAt) {
            $estimate['arriveAt'] = $this->arriveAt->format(\DATE_ATOM);
        }
        if (null !== $this->carLatitude && null !== $this->carLongitude) {
            $estimate['carLatitude'] = $this->carLatitude;
            $estimate['carLongitude'] = $this->carLongitude;
        }

        return $estimate;
    }
}
