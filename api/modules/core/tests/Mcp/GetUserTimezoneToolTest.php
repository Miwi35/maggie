<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\Tool\GetUserTimezoneTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetUserTimezoneToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private const DEFAULT_WORK_HOURS = ['workStartTime' => '09:00', 'workEndTime' => '18:00', 'workDays' => [1, 2, 3, 4, 5]];

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function call(): array
    {
        $tool = self::getContainer()->get(GetUserTimezoneTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundReturnsAnError(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');

        $result = $this->call();

        self::assertArrayHasKey('error', $result);
        self::assertArrayNotHasKey('timezone', $result);
    }

    public function testReturnsTheTimezoneOfTheUserPreferences(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $user = $this->loginFixtureUser();
        $preference = $this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user]);
        $preference->setTimezone('America/New_York');
        $this->em()->flush();

        self::assertSame(['timezone' => 'America/New_York', 'email' => $user->getEmail()] + self::DEFAULT_WORK_HOURS, $this->call());
    }

    public function testFallsBackToParisWhenTheUserHasNoPreferences(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $user = $this->loginFixtureUser();
        $this->em()->remove($this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user]));
        $this->em()->flush();

        self::assertSame(['timezone' => 'Europe/Paris', 'email' => $user->getEmail()] + self::DEFAULT_WORK_HOURS, $this->call());
    }

    public function testNeverReadsAnotherUsersTimezone(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $other = new User();
        $other->setEmail('other@example.com');
        $other->setGoogleId('google-other');
        $other->setName('Other');
        $this->em()->persist($other);
        $otherPreference = (new UserPreference())->setUser($other)->setTimezone('Asia/Tokyo');
        $this->em()->persist($otherPreference);
        $this->em()->flush();

        $user = $this->loginFixtureUser();

        self::assertSame(['timezone' => 'Europe/Paris', 'email' => $user->getEmail()] + self::DEFAULT_WORK_HOURS, $this->call());
    }

    public function testGivesTheWorkingHoursOfTheUserPreferences(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $user = $this->loginFixtureUser();
        $preference = $this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user]);
        $preference->setWorkStartTime('08:30')->setWorkEndTime('17:00')->setWorkDays([1, 2, 3, 4]);
        $this->em()->flush();

        $result = $this->call();

        self::assertSame('08:30', $result['workStartTime']);
        self::assertSame('17:00', $result['workEndTime']);
        self::assertSame([1, 2, 3, 4], $result['workDays']);
    }
}
