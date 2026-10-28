<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Enum\LocationSource;
use Maggie\Core\Mcp\Tool\GetMyLocationTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;

class GetMyLocationToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private const string NOW = '2026-10-28 14:00:00';

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable(self::NOW, new \DateTimeZone('UTC'))));
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_location.yaml');
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function call(): array
    {
        $tool = self::getContainer()->get(GetMyLocationTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function ago(string $interval): \DateTimeImmutable
    {
        return (new \DateTimeImmutable(self::NOW, new \DateTimeZone('UTC')))->modify('-'.$interval);
    }

    private function report(User $user, LocationSource $source, float $latitude, float $accuracy, string $ago): void
    {
        $location = $this->em()->getRepository(UserLocation::class)->findOneBy(['user' => $user]) ?? (new UserLocation())->setUser($user);
        $location->moveTo($source, $latitude, 4.8, $accuracy, $this->ago($ago));
        $this->em()->persist($location);
        $this->em()->flush();
    }

    public function testWithoutAUserBoundReturnsAnError(): void
    {
        $result = $this->call();

        self::assertArrayHasKey('error', $result);
        self::assertArrayNotHasKey('position', $result);
    }

    public function testAllNullWhenThePhoneNeverReported(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['position' => null, 'gps' => null, 'network' => null], $this->call());
    }

    public function testReturnsBothPositionsWithTheirSourceAndAgeAndTrustsTheGpsOne(): void
    {
        $user = $this->loginFixtureUser();
        $this->report($user, LocationSource::Gps, 45.75, 20.0, '40 minutes');
        $this->report($user, LocationSource::Network, 45.78, 500.0, '2 minutes');

        $result = $this->call();

        self::assertSame('gps', $result['gps']['source']);
        self::assertSame(45.75, $result['gps']['latitude']);
        self::assertEquals(20, $result['gps']['accuracy']);
        self::assertSame(40, $result['gps']['ageMinutes']);
        self::assertSame('network', $result['network']['source']);
        self::assertSame(45.78, $result['network']['latitude']);
        self::assertSame(2, $result['network']['ageMinutes']);
        self::assertSame($result['gps'], $result['position']);
    }

    public function testTrustsTheNetworkPositionWhenTheGpsOneIsOverTwoHoursOld(): void
    {
        $user = $this->loginFixtureUser();
        $this->report($user, LocationSource::Gps, 45.75, 20.0, '3 hours');
        $this->report($user, LocationSource::Network, 45.78, 500.0, '2 minutes');

        $result = $this->call();

        self::assertSame('network', $result['position']['source']);
        self::assertSame(180, $result['gps']['ageMinutes']);
    }

    public function testNeverReadsAnotherUsersPosition(): void
    {
        $this->loginFixtureUser();

        $result = $this->call();

        self::assertNull($result['position']);
        self::assertNull($result['gps']);
    }
}
