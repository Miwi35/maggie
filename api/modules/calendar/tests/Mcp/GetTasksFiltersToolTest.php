<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** `get_tasks` filters (MAG-846), on the fixtures of the task views, the clock at 2026-10-13. */
class GetTasksFiltersToolTest extends KernelTestCase
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
    private function call(mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(GetTasksTool::class);

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<string> */
    private function titles(array $data): array
    {
        return array_column($data['tasks'], 'title');
    }

    private function planned(string $title, string $day): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $em->getRepository(User::class)->findOneBy(['email' => 'fixture@example.com']);
        $em->persist((new Task())->setUser($user)->setTitle($title)->schedule(new \DateTimeImmutable($day), TaskMoment::Morning, null, []));
        $em->flush();
    }

    public function testDayReturnsScheduledThatDayAndDueOrLatePending(): void
    {
        $this->loginFixtureUser();
        $this->planned('Planned tomorrow', '2026-10-14');
        $this->planned('Planned the 20th', '2026-10-20');

        $titles = $this->titles($this->call(day: '2026-10-14'));

        foreach (['Planned tomorrow', 'Due tomorrow', 'Due today', 'Late one day', 'Late three days'] as $expected) {
            self::assertContains($expected, $titles);
        }
        self::assertNotContains('Planned the 20th', $titles);
        self::assertNotContains('Undated', $titles);
    }

    public function testDayReturnsOpenTasksPlannedBeforeIt(): void
    {
        $this->loginFixtureUser();
        $this->planned('Planned the 8th', '2026-10-08');

        self::assertContains('Planned the 8th', $this->titles($this->call(day: '2026-10-14')));
        self::assertNotContains('Planned the 8th', $this->titles($this->call(day: '2026-10-07')));
    }

    public function testDayLeavesOutADoneTaskPlannedBefore(): void
    {
        $this->loginFixtureUser();
        $this->planned('Planned the 8th, done', '2026-10-08');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getRepository(Task::class)->findOneBy(['title' => 'Planned the 8th, done'])->setCompletedAt(new \DateTimeImmutable('2026-10-09 10:00:00 UTC'));
        $em->flush();

        self::assertNotContains('Planned the 8th, done', $this->titles($this->call(day: '2026-10-13', status: 'all')));
    }

    public function testDayExcludesDoneTasksDueThatDay(): void
    {
        $this->loginFixtureUser();

        self::assertNotContains('Late but done', $this->titles($this->call(day: '2026-10-13')));
    }

    public function testDatedFalseReturnsTasksWithoutDueNorScheduledDate(): void
    {
        $this->loginFixtureUser();
        $this->planned('Planned tomorrow', '2026-10-14');

        $titles = $this->titles($this->call(dated: false));

        self::assertContains('Undated', $titles);
        self::assertNotContains('Planned tomorrow', $titles);
        self::assertNotContains('Due today', $titles);
        self::assertContains('Planned tomorrow', $this->titles($this->call(dated: true)));
    }

    public function testDueRangeIsInclusive(): void
    {
        $this->loginFixtureUser();

        $titles = $this->titles($this->call(dueFrom: '2026-10-12', dueTo: '2026-10-13'));

        self::assertEqualsCanonicalizing(['Late one day', 'Due today', 'Late subtask of a future task'], $titles);
    }

    public function testScheduledRangeIsInclusive(): void
    {
        $this->loginFixtureUser();
        $this->planned('A', '2026-10-14');
        $this->planned('B', '2026-10-15');
        $this->planned('C', '2026-10-16');

        self::assertEqualsCanonicalizing(['A', 'B'], $this->titles($this->call(scheduledFrom: '2026-10-14', scheduledTo: '2026-10-15')));
    }

    public function testParentIdReturnsSubtasks(): void
    {
        $this->loginFixtureUser();
        $parent = $this->getFixture('late_three_days');

        $titles = $this->titles($this->call(status: 'all', parentId: (string) $parent->getId()));

        self::assertEqualsCanonicalizing(['Sub of late, first', 'Sub of late, second', 'Sub of late, done'], $titles);
    }

    public function testStatusDoneOrdersByCompletionDesc(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(status: 'done');

        self::assertSame(['Sub of late, done', 'Late but done'], $this->titles($data));
        self::assertSame(2, $data['total']);
    }

    public function testMalformedDateNamesTheField(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('dueFrom', $this->call(dueFrom: 'demain')['error']);
        self::assertStringContainsString('day', $this->call(day: '2026-02-30')['error']);
    }

    public function testInvertedRangeIsRefused(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(scheduledFrom: '2026-10-15', scheduledTo: '2026-10-14');

        self::assertStringContainsString('scheduledTo', $data['error']);
        self::assertArrayNotHasKey('tasks', $data);
    }

    public function testLimitIsCappedAt200(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(status: 'all', limit: 1);
        self::assertSame(1, $data['count']);
        self::assertGreaterThan(1, $data['total']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = $em->getRepository(User::class)->findOneBy(['email' => 'fixture@example.com']);
        for ($i = 0; $i < 205; ++$i) {
            $em->persist((new Task())->setUser($user)->setTitle("Bulk {$i}"));
        }
        $em->flush();

        $data = $this->call(status: 'all', limit: 1000);
        self::assertSame(200, $data['count']);
        self::assertGreaterThan(200, $data['total']);
    }

    public function testAnotherUsersTasksNeverComeBack(): void
    {
        $this->loginFixtureUser();

        $titles = $this->titles($this->call(status: 'all'));

        self::assertNotContains("Someone else's late task", $titles);
        self::assertContains('Undated', $titles);
    }

    public function testWithoutUserReturnsError(): void
    {
        $data = $this->call(status: 'all');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('tasks', $data);
    }
}
