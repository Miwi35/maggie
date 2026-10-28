<?php

namespace Maggie\Calendar\Tests\Specification;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Calendar\Specification\IsUserAwayFromTrip;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Enum\LocationSource;
use Maggie\Core\Event\UserLocationChangedEvent;
use Maggie\Core\Location\LocationFix;
use PHPUnit\Framework\TestCase;

/** MAG-656. The trip starts from home (48.11, -1.68); a hundredth of a degree of latitude is 1.1 km. */
class IsUserAwayFromTripTest extends TestCase
{
    private function trip(TripMode $mode, ?float $carLatitude = null, ?float $carLongitude = null): Trip
    {
        return (new Trip(new Event()))->apply(new TripEstimate(
            mode: $mode,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 48.11, -1.68),
            steps: [new RouteLeg($mode, 600)],
            durationMinutes: 10,
            departAt: new \DateTimeImmutable('2026-11-03 13:34:00 Europe/Paris'),
            navigationUrl: 'https://waze.com/ul',
            parkingKnown: false,
            carLatitude: $carLatitude,
            carLongitude: $carLongitude,
        ), TripDecider::Maggie, new \DateTimeImmutable('2026-11-03 10:00:00 UTC'));
    }

    private function position(float $latitude): LocationFix
    {
        return new LocationFix($latitude, -1.68, 10.0, new \DateTimeImmutable('2026-11-03 10:00:00 UTC'), LocationSource::Gps);
    }

    private function car(float $latitude): UserLocation
    {
        return (new UserLocation())->parkCar($latitude, -1.68, new \DateTimeImmutable('2026-11-03 10:00:00 UTC'));
    }

    public function testAPositionTwoKilometresFromTheOriginSatisfiesIt(): void
    {
        $specification = new IsUserAwayFromTrip(new UserLocation(), $this->position(48.128), UserLocationChangedEvent::POSITION);

        self::assertTrue($specification->isSatisfiedBy($this->trip(TripMode::Transit)));
    }

    public function testAPositionAHundredMetresFromTheOriginDoesNot(): void
    {
        $specification = new IsUserAwayFromTrip(new UserLocation(), $this->position(48.1109), UserLocationChangedEvent::POSITION);

        self::assertFalse($specification->isSatisfiedBy($this->trip(TripMode::Transit)));
    }

    public function testThePositionIsComparedWhateverTheMode(): void
    {
        $specification = new IsUserAwayFromTrip(new UserLocation(), $this->position(48.128), UserLocationChangedEvent::POSITION);

        foreach (TripMode::cases() as $mode) {
            self::assertTrue($specification->isSatisfiedBy($this->trip($mode)), $mode->value);
        }
    }

    public function testACarMovedFiveHundredMetresFromTheOneTheTripTookSatisfiesIt(): void
    {
        $specification = new IsUserAwayFromTrip($this->car(48.1172), null, UserLocationChangedEvent::CAR);

        self::assertTrue($specification->isSatisfiedBy($this->trip(TripMode::Drive, 48.1127, -1.68)));
    }

    public function testACarAHundredMetresFromTheOneTheTripTookDoesNot(): void
    {
        $specification = new IsUserAwayFromTrip($this->car(48.1136), null, UserLocationChangedEvent::CAR);

        self::assertFalse($specification->isSatisfiedBy($this->trip(TripMode::Drive, 48.1127, -1.68)));
    }

    public function testACarMatterNothingToATripByTransit(): void
    {
        $specification = new IsUserAwayFromTrip($this->car(48.2), null, UserLocationChangedEvent::CAR);

        self::assertFalse($specification->isSatisfiedBy($this->trip(TripMode::Transit)));
        self::assertFalse($specification->isSatisfiedBy($this->trip(TripMode::Walk)));
    }

    public function testACarNobodyKnewIsTakenWhereTheTripStarts(): void
    {
        $far = new IsUserAwayFromTrip($this->car(48.128), null, UserLocationChangedEvent::CAR);
        $near = new IsUserAwayFromTrip($this->car(48.1105), null, UserLocationChangedEvent::CAR);

        self::assertTrue($far->isSatisfiedBy($this->trip(TripMode::Drive)));
        self::assertFalse($near->isSatisfiedBy($this->trip(TripMode::Drive)));
    }

    public function testAMissingHalfOfTheLocationDoesNot(): void
    {
        // The phone reported the car before any position.
        self::assertFalse((new IsUserAwayFromTrip($this->car(48.2), null, UserLocationChangedEvent::POSITION))->isSatisfiedBy($this->trip(TripMode::Drive)));
        self::assertFalse((new IsUserAwayFromTrip(new UserLocation(), $this->position(48.2), UserLocationChangedEvent::CAR))->isSatisfiedBy($this->trip(TripMode::Drive)));
    }
}
