<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Repository\PlaceRepository;
use Maggie\Calendar\Service\PlaceMatcher;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The known place a point stands at (MAG-652): the nearest within 150 m, the user's only. */
class PlaceMatcherTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('PlaceMatcherTest.yaml');
    }

    private function match(float $latitude, float $longitude, string $user = 'test_user'): ?string
    {
        $owner = $this->getFixture($user);
        self::assertInstanceOf(User::class, $owner);

        // Read by the trips only (parts 4 and 5): not wired yet, so built here.
        return (new PlaceMatcher(self::getContainer()->get(PlaceRepository::class)))->match($owner, $latitude, $longitude)?->getName();
    }

    public function testTheHaversineDistanceIsInMeters(): void
    {
        self::assertEqualsWithDelta(100.1, PlaceMatcher::distance(48.11, -1.68, 48.1109, -1.68), 0.5);
        self::assertEqualsWithDelta(0.0, PlaceMatcher::distance(48.11, -1.68, 48.11, -1.68), 0.001);
    }

    public function testOfTwoPlacesWithinTheRadiusTheNearestIsFound(): void
    {
        // « À 100 m » and « À 120 m » both qualify; the neighbour's, at 10 m, is not the user's.
        self::assertSame('À 100 m', $this->match(48.1100, -1.6800));
    }

    public function testAPlaceAt200MetersIsNotAMatch(): void
    {
        // 200 m from « À 200 m », 500 m and more from the others.
        self::assertNull($this->match(48.1064, -1.6800));
    }

    public function testAPlaceWithoutCoordinatesIsNeverMatched(): void
    {
        self::assertNull($this->match(0.0, 0.0));
    }

    public function testAnotherUsersPlacesAreIgnored(): void
    {
        self::assertSame('Chez le voisin', $this->match(48.1100, -1.6800, 'other_user'));
        self::assertNull($this->match(48.1082, -1.6800, 'other_user'));
    }
}
