<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
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

    private function report(User $user, float $latitude, float $accuracy, string $ago): void
    {
        $location = $this->em()->getRepository(UserLocation::class)->findOneBy(['user' => $user]) ?? (new UserLocation())->setUser($user);
        $location->moveTo($latitude, 4.8, $accuracy, $this->ago($ago));
        $this->em()->persist($location);
        $this->em()->flush();
    }

    public function testWithoutAUserBoundReturnsAnError(): void
    {
        $result = $this->call();

        self::assertArrayHasKey('error', $result);
        self::assertArrayNotHasKey('position', $result);
    }

    public function testNullWhenThePhoneNeverReported(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['position' => null], $this->call());
    }

    public function testReturnsTheLastGpsPointWithItsAge(): void
    {
        $user = $this->loginFixtureUser();
        $this->report($user, 45.75, 20.0, '40 minutes');

        $position = $this->call()['position'];

        self::assertSame(45.75, $position['latitude']);
        self::assertEquals(20, $position['accuracy']);
        self::assertSame(40, $position['ageMinutes']);
        self::assertArrayNotHasKey('source', $position);
    }

    /** MAG-666, recette of 28 Oct.: a phone that stayed still for hours still has its last GPS point. */
    public function testTheLastGpsPointStaysThePositionWhateverItsAge(): void
    {
        $user = $this->loginFixtureUser();
        $this->report($user, 45.75, 20.0, '5 hours');

        $position = $this->call()['position'];

        self::assertSame(45.75, $position['latitude']);
        self::assertSame(300, $position['ageMinutes']);
    }

    public function testNeverReadsAnotherUsersPosition(): void
    {
        $this->loginFixtureUser();

        $result = $this->call();

        self::assertNull($result['position']);
    }
}
