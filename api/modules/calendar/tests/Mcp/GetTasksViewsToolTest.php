<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `get_tasks` with `view`: today, upcoming, someday, done (MAG-408). */
class GetTasksViewsToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-13 12:00:00 UTC'));
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/TaskViewsApiTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $view): array
    {
        $tool = self::getContainer()->get(GetTasksTool::class);

        return json_decode($tool(view: $view), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testRefusesAnUnauthenticatedCall(): void
    {
        $data = $this->call('upcoming');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testRefusesAnUnknownViewAndNamesTheKnownOnes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('yesterday');

        self::assertStringContainsString('Unknown view', $data['error']);
        self::assertStringContainsString('someday', $data['error']);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testUpcomingIsByDueDayThenByScore(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('upcoming');

        self::assertSame('upcoming', $data['view']);
        self::assertSame([
            'Due tomorrow',
            'Due the 18th, critical',
            'Due the 18th, low',
            'Due the 30th',
            'Dated subtask of a far task',
            'Undated subtask of a far task',
        ], array_column($data['tasks'], 'title'));
        self::assertSame(6, $data['count']);
        self::assertSame('2026-10-14', $data['tasks'][0]['dueDate']);
    }

    public function testSomedayIsByPriorityThenByPosition(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('someday');

        self::assertSame([
            'Someday, high priority',
            'Someday, medium first',
            'Someday, medium second',
            'Someday, medium unpositioned',
            'Someday, low priority',
            'Someday, parent of a dated subtask',
            'Subtask of a someday task, due tomorrow',
        ], array_column($data['tasks'], 'title'));
        self::assertSame([null, null, null, null, null, null, '2026-10-14'], array_column($data['tasks'], 'dueDate'));
    }

    public function testDoneIsTheMostRecentlyDoneFirst(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('done');

        self::assertSame(
            ['Done yesterday', 'Done subtask', 'Done last week', 'Done a while ago'],
            array_column($data['tasks'], 'title'),
        );
        self::assertSame(4, $data['total']);
        self::assertNotNull($data['tasks'][0]['completedAt']);
    }

    public function testEveryViewCarriesTheSizeOfTheFour(): void
    {
        $this->loginFixtureUser();

        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            self::assertSame(['today' => 3, 'upcoming' => 6, 'someday' => 7, 'done' => 4], $this->call($view)['counts']);
        }
    }

    public function testNoViewShowsAnotherUsersTasks(): void
    {
        $this->loginFixtureUser();

        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            foreach (array_column($this->call($view)['tasks'], 'title') as $title) {
                self::assertStringNotContainsString('Someone else', $title);
            }
        }
    }
}
