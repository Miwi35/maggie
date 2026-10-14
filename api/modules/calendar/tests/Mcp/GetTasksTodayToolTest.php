<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `get_tasks` with `view: today`, and the lateness every task of the tools carries (MAG-405). */
class GetTasksTodayToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-13 12:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/TaskViewApiTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(?string $status = null, ?string $view = null): array
    {
        $tool = self::getContainer()->get(GetTasksTool::class);
        $result = null === $status ? $tool(view: $view) : $tool($status, null, $view);

        return json_decode($result, true, 512, JSON_THROW_ON_ERROR);
    }

    public function testRefusesAnUnauthenticatedCall(): void
    {
        $data = $this->call(view: 'today');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testRefusesAnUnknownView(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(view: 'yesterday');

        self::assertStringContainsString('Unknown view', $data['error']);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testTodayHoldsTheLateTasksFirstThenTodaysEachFollowedByItsSubtasks(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(view: 'today');

        self::assertSame('today', $data['view']);
        self::assertSame(6, $data['count']);
        self::assertSame([
            'Late three days',
            'Sub of late, first',
            'Sub of late, second',
            'Late subtask of a future task',
            'Late one day',
            'Due today',
        ], array_column($data['tasks'], 'title'));
        self::assertSame([3, null, null, 1, 1, null], array_column($data['tasks'], 'overdueDays'));
        self::assertSame('2026-10-10', $data['tasks'][0]['dueDate'], 'the original due day stays');
    }

    public function testTodayDoesNotShowAnotherUsersTasks(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->call(view: 'today');

        self::assertSame(["Someone else's late task"], array_column($data['tasks'], 'title'));
        self::assertSame(12, $data['tasks'][0]['overdueDays']);
    }

    public function testTheStatusListsCarryTheLatenessToo(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('overdue');

        $byTitle = array_column($data['tasks'], 'overdueDays', 'title');
        self::assertSame(3, $byTitle['Late three days']);
        self::assertSame(1, $byTitle['Late one day']);
    }
}
