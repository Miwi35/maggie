<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** « mets-la vendredi soir », « vendredi à 18 h », « avant samedi » through Maggie's tools (MAG-718). */
final class TaskScheduleToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UpdateTaskToolTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testTheToolsNeedAUser(): void
    {
        $created = $this->decode((self::getContainer()->get(CreateTaskTool::class))('X', scheduledDate: '2037-01-02'));
        $updated = $this->decode($this->update(scheduledDate: '2037-01-02'));

        self::assertSame(MissingMcpUserException::MESSAGE, $created['error']);
        self::assertArrayHasKey('error', $updated);
        $this->assertMercureUpdateCount(0);
    }

    public function testCreateAtAPreciseTimeGetsTheDefaultReminder(): void
    {
        $this->loginFixtureUser();

        $data = $this->decode((self::getContainer()->get(CreateTaskTool::class))('Kiné', scheduledDate: '2037-01-02', scheduledMoment: 'time', scheduledTime: '18:00'));

        self::assertTrue($data['success']);
        self::assertSame(['2037-01-02', 'time', '18:00', [0]], [$data['task']['scheduledDate'], $data['task']['scheduledMoment'], $data['task']['scheduledTime'], $data['task']['reminders']]);
        $task = $this->reload($data['task']['id']);
        self::assertSame(TaskMoment::Time, $task->getScheduledMoment());
        self::assertSame([0], $task->getReminders());
        self::assertNull($task->getDueDate());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testFridayEveningPlansTheEvening(): void
    {
        $this->loginFixtureUser();

        $data = $this->decode($this->update(scheduledDate: '2037-01-02', scheduledMoment: 'evening'));

        self::assertTrue($data['success']);
        $task = $this->reload();
        self::assertSame('2037-01-02', $task->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(TaskMoment::Evening, $task->getScheduledMoment());
        self::assertNull($task->getScheduledTime());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testFridayAt6pmTakesTheTimeAndTheRemindersSaid(): void
    {
        $this->loginFixtureUser();

        $this->decode($this->update(scheduledDate: '2037-01-02', scheduledMoment: 'time', scheduledTime: '18:00', reminders: [30]));

        $task = $this->reload();
        self::assertSame('18:00', $task->getScheduledTime());
        self::assertSame([30], $task->getReminders());
    }

    public function testATimeOrRemindersWithoutAPreciseTimeAreAnError(): void
    {
        $this->loginFixtureUser();

        $time = $this->decode($this->update(scheduledDate: '2037-01-02', scheduledMoment: 'evening', scheduledTime: '18:00'));
        $reminders = $this->decode($this->update(scheduledDate: '2037-01-02', scheduledMoment: 'evening', reminders: [30]));
        $day = $this->decode($this->update(scheduledDate: '2037-02-30'));

        self::assertStringContainsString('scheduledTime', $time['error']);
        self::assertStringContainsString('Reminders', $reminders['error']);
        self::assertStringContainsString('scheduledDate must be a day', $day['error']);
        self::assertNull($this->reload()->getScheduledDate());
        $this->assertMercureUpdateCount(0);
    }

    public function testClearScheduledDateEmptiesTheProgrammationAndKeepsTheDueDay(): void
    {
        $this->loginFixtureUser();
        $this->decode($this->update(scheduledDate: '2037-01-02', scheduledMoment: 'time', scheduledTime: '18:00', dueDate: '2037-01-03'));

        $data = $this->decode($this->update(clear: ['scheduledDate']));

        self::assertTrue($data['success']);
        $task = $this->reload();
        self::assertNull($task->getScheduledDate());
        self::assertNull($task->getScheduledMoment());
        self::assertSame([], $task->getReminders());
        self::assertSame('2037-01-03', $task->getDueDate()?->format('Y-m-d'), 'before Saturday stays the deadline');
    }

    /**
     * @param list<string>|null $clear
     * @param list<int>|null    $reminders
     */
    private function update(?string $scheduledDate = null, ?string $scheduledMoment = null, ?string $scheduledTime = null, ?array $reminders = null, ?array $clear = null, ?string $dueDate = null): string
    {
        return (self::getContainer()->get(UpdateTaskTool::class))(
            (string) $this->getFixture('task_done')->getId(),
            dueDate: $dueDate,
            clear: $clear,
            scheduledDate: $scheduledDate,
            scheduledMoment: $scheduledMoment,
            scheduledTime: $scheduledTime,
            reminders: $reminders,
        );
    }

    /** @return array<string, mixed> */
    private function decode(string $json): array
    {
        return json_decode($json, true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(?string $id = null): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $task = $em->getRepository(Task::class)->find($id ?? $this->getFixture('task_done')->getId());
        self::assertInstanceOf(Task::class, $task);

        return $task;
    }
}
