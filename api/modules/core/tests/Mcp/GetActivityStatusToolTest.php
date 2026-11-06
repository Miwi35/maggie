<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Enum\Activity;
use Maggie\Core\Mcp\Tool\GetActivityStatusTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** `get_activity_status` (MAG-747): the agent resumes an activity where the user left it. */
class GetActivityStatusToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/activity_status.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function call(string $activity): mixed
    {
        $tool = self::getContainer()->get(GetActivityStatusTool::class);

        return json_decode($tool($activity), true, 512, JSON_THROW_ON_ERROR);
    }

    private function startBilan(): void
    {
        $now = new \DateTimeImmutable('2026-10-25 20:00:00 UTC');
        $status = (new ActivityStatus())
            ->setUser($this->getFixture('test_user'))
            ->setActivity(Activity::Bilan)
            ->setOrigin('day', '2026-10-25')
            ->setState('{"step":"sante","answers":{},"instructions":[]}')
            ->setStartedAt($now)
            ->setUpdatedAt($now);
        $this->em()->persist($status);
        $this->em()->flush();
    }

    public function testWithoutAUserBoundReturnsAnError(): void
    {
        self::assertArrayHasKey('error', $this->call('bilan'));
    }

    public function testReturnsTheStatusOfTheUser(): void
    {
        $this->startBilan();
        $this->loginUser($this->getFixture('test_user'));

        $result = $this->call('bilan');

        self::assertSame('bilan', $result['activity']);
        self::assertSame(['kind' => 'day', 'id' => '2026-10-25'], $result['origin']);
        self::assertSame('sante', $result['state']['step']);
    }

    public function testReturnsNullWhenNothingIsInProgress(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertNull($this->call('bilan'));
    }

    public function testNeverReadsAnotherUsersStatus(): void
    {
        // other_user is cooking; test_user is not.
        $this->loginUser($this->getFixture('test_user'));

        self::assertNull($this->call('cuisine'));
    }

    public function testAnUnknownActivityReturnsAnError(): void
    {
        $this->loginUser($this->getFixture('test_user'));

        self::assertArrayHasKey('error', $this->call('jardin'));
    }
}
