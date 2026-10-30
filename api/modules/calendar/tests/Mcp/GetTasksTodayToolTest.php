<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Core\Entity\User;
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

    public function testATaskPlannedTodayWithoutADueDayIsInFirstByMomentTomorrowsIsNot(): void
    {
        $user = $this->loginFixtureUser();
        $this->planned($user, 'Ce soir', '2026-10-13', TaskMoment::Evening);
        $this->planned($user, 'À 9 h', '2026-10-13', TaskMoment::Time, '09:00');
        $this->planned($user, 'Ce matin', '2026-10-13', TaskMoment::Morning);
        $this->planned($user, 'À 18 h', '2026-10-13', TaskMoment::Time, '18:00');
        $this->planned($user, 'Demain soir', '2026-10-14', TaskMoment::Evening);

        $data = $this->call(view: 'today');

        $titles = array_column($data['tasks'], 'title');
        self::assertSame(['Ce matin', 'À 9 h', 'Ce soir', 'À 18 h'], \array_slice($titles, 0, 4), 'the plan of the day first, by moment then time');
        self::assertSame('Late three days', $titles[4], 'then the late ones, by score');
        self::assertNotContains('Demain soir', $titles);
        self::assertSame(10, $data['counts']['today']);
        self::assertSame('evening', $data['tasks'][2]['scheduledMoment']);
        self::assertSame('18:00', $data['tasks'][3]['scheduledTime']);

        $upcoming = $this->call(view: 'upcoming');
        self::assertContains('Demain soir', array_column($upcoming['tasks'], 'title'), 'planned tomorrow: upcoming, not someday');
        self::assertNotContains('Demain soir', array_column($this->call(view: 'someday')['tasks'], 'title'));
    }

    public function testTodayCarriesTheRemindersTheDigestTells(): void
    {
        $user = $this->loginFixtureUser();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist((new Task())->setUser($em->find(User::class, $user->getId()))->setTitle('Déclarer les impôts')
            ->setCriticality(TaskCriticality::Critical)->setDueDate(new \DateTimeImmutable('2026-10-16')));
        $planned = (new Task())->setUser($em->find(User::class, $user->getId()))->setTitle('Impôts planifiés')
            ->setCriticality(TaskCriticality::Critical)->setDueDate(new \DateTimeImmutable('2026-10-16'))
            ->schedule(new \DateTimeImmutable('2026-10-15'), TaskMoment::Morning, null, []);
        $em->persist($planned);
        $em->flush();

        $reminders = $this->call(view: 'today')['reminders'];

        $byTitle = array_column($reminders, null, 'title');
        self::assertSame(['taskId', 'title', 'reason', 'days', 'urgency'], array_keys($byTitle['Déclarer les impôts']));
        self::assertSame(['to_schedule', 3, 'critical'], [$byTitle['Déclarer les impôts']['reason'], $byTitle['Déclarer les impôts']['days'], $byTitle['Déclarer les impôts']['urgency']]);
        self::assertArrayNotHasKey('Impôts planifiés', $byTitle, 'planned: no longer to plan');
        self::assertContains('not_done', array_column($reminders, 'reason'), 'the late ones of the fixtures');
        self::assertArrayNotHasKey('reminders', $this->call(view: 'upcoming'));
    }

    private function planned(User $user, string $title, string $day, TaskMoment $moment, ?string $time = null): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist((new Task())->setUser($em->find(User::class, $user->getId()))->setTitle($title)
            ->schedule(new \DateTimeImmutable($day), $moment, $time, null === $time ? [] : [0]));
        $em->flush();
    }
}
