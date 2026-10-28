<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Service;

use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Enum\LocationSource;
use Maggie\Core\Repository\UserLocationRepository;
use Maggie\Core\Service\UserPositions;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Clock\MockClock;

/** The one place that chooses between the GPS and the network position (MAG-666). */
class UserPositionsTest extends TestCase
{
    private const string NOW = '2026-10-28 14:00:00';

    private function positions(?UserLocation $location): UserPositions
    {
        $repository = $this->createStub(UserLocationRepository::class);
        $repository->method('findOneByUser')->willReturn($location);

        return new UserPositions($repository, new MockClock(new \DateTimeImmutable(self::NOW, new \DateTimeZone('UTC'))));
    }

    private function ago(string $interval): \DateTimeImmutable
    {
        return (new \DateTimeImmutable(self::NOW, new \DateTimeZone('UTC')))->modify('-'.$interval);
    }

    private function location(?string $gpsAgo, ?string $networkAgo): UserLocation
    {
        $location = new UserLocation();
        if (null !== $gpsAgo) {
            $location->moveTo(LocationSource::Gps, 48.0, -1.0, 20.0, $this->ago($gpsAgo));
        }
        if (null !== $networkAgo) {
            $location->moveTo(LocationSource::Network, 48.1, -1.1, 500.0, $this->ago($networkAgo));
        }

        return $location;
    }

    public function testNobodyHasNoPositionWhenThePhoneNeverReported(): void
    {
        self::assertNull($this->positions(null)->of(new User()));
        self::assertNull($this->positions(new UserLocation())->of(new User()));
    }

    public function testTheGpsPositionAloneIsTheOne(): void
    {
        $fix = $this->positions($this->location('5 days', null))->of(new User());

        self::assertSame(LocationSource::Gps, $fix->source);
    }

    public function testTheNetworkPositionAloneIsTheOneWhateverItsAge(): void
    {
        $fix = $this->positions($this->location(null, '5 days'))->of(new User());

        self::assertSame(LocationSource::Network, $fix->source);
    }

    /** @return iterable<string, array{string, string, LocationSource}> */
    public static function both(): iterable
    {
        yield 'fresh GPS beats a newer network fix (28 Oct.)' => ['40 minutes', '1 minute', LocationSource::Gps];
        yield 'GPS just under two hours old stays' => ['119 minutes', '1 minute', LocationSource::Gps];
        yield 'GPS just over two hours old gives way to a newer network fix' => ['121 minutes', '1 minute', LocationSource::Network];
        yield 'a network fix older than a stale GPS one never wins' => ['3 hours', '5 hours', LocationSource::Gps];
        yield 'both stale, the newer network fix wins' => ['6 hours', '3 hours', LocationSource::Network];
    }

    #[DataProvider('both')]
    public function testTheGpsPositionIsTheDefaultAndTheNetworkOneOnlyReplacesAStaleGps(string $gpsAgo, string $networkAgo, LocationSource $expected): void
    {
        $fix = $this->positions($this->location($gpsAgo, $networkAgo))->of(new User());

        self::assertSame($expected, $fix->source);
    }
}
