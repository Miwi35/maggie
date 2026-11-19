<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Maggie\Calendar\Tests\Support\RecurringTaskTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\NativeClock;

/** « Rappelle-moi un mardi sur deux de sortir la poubelle jaune » through Maggie's tools (MAG-870). */
final class RecurringTaskToolsTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use RecurringTaskTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(\dirname(__DIR__).'/MessageHandler/fixtures/TaskRecurrenceTest.yaml');
        $this->today('2026-11-19');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testCreateTaskWithARuleStoresOneRecurringTask(): void
    {
        $this->loginFixtureUser();

        $data = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY);

        self::assertTrue($data['success']);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $data['task']['rrule']);
        $series = $this->series();
        self::assertCount(1, $series);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $series[0]->getRrule());
        self::assertSame('2026-11-24', $series[0]->getRecurrenceDate()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testCreateTaskRefusesARuleWithoutADayAndSaysWhatItHas(): void
    {
        $this->loginFixtureUser();

        $data = $this->create(rrule: self::EVERY_OTHER_TUESDAY);

        self::assertStringContainsString('give a scheduledDate or a dueDate', $data['error']);
        self::assertStringContainsString('This task has neither', $data['error']);
        $this->assertNothingWritten();
    }

    public function testCreateTaskRefusesCount(): void
    {
        $this->loginFixtureUser();

        $data = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY.';COUNT=3');

        self::assertStringContainsString('UNTIL', $data['error']);
        $this->assertNothingWritten();
    }

    public function testCreateTaskRefusesADayOutsideTheRule(): void
    {
        $this->loginFixtureUser();

        $data = $this->create(scheduledDate: '2026-11-25', rrule: 'FREQ=WEEKLY;BYDAY=TU');

        self::assertStringContainsString('2026-12-01 (Tuesday)', $data['error']);
        $this->assertNothingWritten();
    }

    public function testCreateTaskRefusesAPrefixedOrHourlyRuleAndASubtask(): void
    {
        $this->loginFixtureUser();
        $parent = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Ménage'));
        $this->resetMercure();
        $this->resetAsyncTransport();

        $prefixed = $this->create(scheduledDate: '2026-11-24', rrule: 'RRULE:'.self::EVERY_OTHER_TUESDAY);
        $hourly = $this->create(scheduledDate: '2026-11-24', rrule: 'FREQ=HOURLY');
        $subtask = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Sortir la poubelle jaune', parentId: $parent['task']['id'], scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY));

        self::assertStringContainsString('"RRULE:" prefix', $prefixed['error']);
        self::assertStringContainsString('DAILY, WEEKLY, MONTHLY or YEARLY', $hourly['error']);
        self::assertStringContainsString('A subtask cannot repeat', $subtask['error']);
        $this->assertNothingWritten();
    }

    public function testUpdateTaskSetsARuleOnTheCurrentDay(): void
    {
        $this->loginFixtureUser();
        $id = $this->create(scheduledDate: '2026-11-24')['task']['id'];

        $data = $this->update($id, rrule: self::EVERY_OTHER_TUESDAY);

        self::assertSame(self::EVERY_OTHER_TUESDAY, $data['task']['rrule']);
        self::assertSame('2026-11-24', $this->series()[0]->getRecurrenceDate()?->format('Y-m-d'));
    }

    public function testUpdateTaskClearRruleStopsTheSeries(): void
    {
        $this->loginFixtureUser();
        $id = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY)['task']['id'];

        $cleared = $this->update($id, clear: ['rrule']);
        $this->update($id, done: true);

        self::assertNull($cleared['task']['rrule']);
        $series = $this->series();
        self::assertCount(1, $series, 'ticking no longer creates anything');
        self::assertNull($series[0]->getRrule());
    }

    public function testUpdateTaskClearingTheOnlyDayOfARecurringTaskIsRefused(): void
    {
        $this->loginFixtureUser();
        $id = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY)['task']['id'];
        $this->resetMercure();

        $data = $this->update($id, clear: ['scheduledDate']);

        self::assertStringContainsString('This task has neither', $data['error']);
        self::assertSame('2026-11-24', $this->series()[0]->getScheduledDate()?->format('Y-m-d'));
        $this->assertMercureUpdateCount(0);
    }

    public function testUpdateTaskMovingTheDayKeepsTheSeriesDay(): void
    {
        $this->loginFixtureUser();
        $id = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY)['task']['id'];

        $data = $this->update($id, scheduledDate: '2026-11-25');

        self::assertTrue($data['success']);
        $task = $this->series()[0];
        self::assertSame(['2026-11-25', '2026-11-24'], [$task->getScheduledDate()?->format('Y-m-d'), $task->getRecurrenceDate()?->format('Y-m-d')]);
    }

    public function testDoneTrueCreatesTheNextOccurrence(): void
    {
        $this->loginFixtureUser();
        $id = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY)['task']['id'];
        $this->today('2026-11-24');

        $data = $this->update($id, done: true);

        self::assertNull($data['task']['rrule']);
        [$done, $next] = $this->series();
        self::assertTrue($done->isDone());
        self::assertSame('2026-12-08', $next->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(self::EVERY_OTHER_TUESDAY, $next->getRrule());
    }

    public function testGetTasksReturnsTheRule(): void
    {
        $this->loginFixtureUser();
        $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY);

        $data = $this->decode((self::getContainer()->get(GetTasksTool::class))());

        $rules = array_column($data['tasks'], 'rrule', 'title');
        self::assertSame(self::EVERY_OTHER_TUESDAY, $rules['Sortir la poubelle jaune']);
    }

    public function testWithoutUserReturnsTheError(): void
    {
        $created = $this->create(scheduledDate: '2026-11-24', rrule: self::EVERY_OTHER_TUESDAY);
        $updated = $this->update('01JAAAAAAAAAAAAAAAAAAAAAAA', clear: ['rrule']);

        self::assertSame(MissingMcpUserException::MESSAGE, $created['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $updated['error']);
        $this->assertNothingWritten();
    }

    public function testAnotherUserSTaskIsNotFound(): void
    {
        $other = $this->createRecurring($this->getFixture('other_user'));
        $this->loginFixtureUser();
        $this->resetMercure();

        $data = $this->update((string) $other->getId(), clear: ['rrule']);

        self::assertStringContainsString('Task not found', $data['error']);
        self::assertSame(self::EVERY_OTHER_TUESDAY, $this->series()[0]->getRrule());
        $this->assertMercureUpdateCount(0);
    }

    /** @return array<string, mixed> */
    private function create(?string $scheduledDate = null, ?string $rrule = null): array
    {
        return $this->decode((self::getContainer()->get(CreateTaskTool::class))('Sortir la poubelle jaune', scheduledDate: $scheduledDate, rrule: $rrule));
    }

    /**
     * @param list<string>|null $clear
     *
     * @return array<string, mixed>
     */
    private function update(string $id, ?string $rrule = null, ?array $clear = null, ?bool $done = null, ?string $scheduledDate = null): array
    {
        return $this->decode((self::getContainer()->get(UpdateTaskTool::class))($id, done: $done, clear: $clear, scheduledDate: $scheduledDate, rrule: $rrule));
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    private function assertNothingWritten(): void
    {
        self::assertSame([], $this->series());
        $this->assertMercureUpdateCount(0);
    }
}
