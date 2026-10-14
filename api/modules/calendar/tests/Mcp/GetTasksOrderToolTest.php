<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `get_tasks` carries the urgency and the order score, and lists by score (MAG-406). */
class GetTasksOrderToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-13 12:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/TaskSettingsApiTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $status = 'pending', ?string $view = null): array
    {
        $tool = self::getContainer()->get(GetTasksTool::class);

        return json_decode($tool($status, null, $view), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testRefusesAnUnauthenticatedCall(): void
    {
        self::assertArrayHasKey('error', $this->call());
    }

    public function testTodayIsInScoreOrderWithTheUrgencyOfEachTask(): void
    {
        $this->loginFixtureUser();

        $tasks = $this->call(view: 'today')['tasks'];

        self::assertSame(['Payer la cantine', 'Rendre le livre', 'Arroser'], array_column($tasks, 'title'));
        self::assertSame([78, 67, 55], array_column($tasks, 'orderScore'));
        self::assertSame(['high', 'high', 'normal'], array_column($tasks, 'urgency'));
    }

    public function testThePendingTasksComeHighestScoreFirst(): void
    {
        $this->loginFixtureUser();

        $tasks = $this->call('pending')['tasks'];

        self::assertSame(['Payer la cantine', 'Rendre le livre', 'Arroser'], array_column($tasks, 'title'));
    }
}
