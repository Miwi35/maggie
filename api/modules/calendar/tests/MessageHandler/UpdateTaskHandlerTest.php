<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use PHPUnit\Framework\MockObject\MockObject;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateTaskHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateTaskHandlerTest.yaml');
    }

    private function dispatch(UpdateTaskCommand $command): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch($command);
    }

    private function reload(): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Task::class)->find($this->getFixture('task_done')->getId());
    }

    public function testClearingCompletedAtReopensTheTask(): void
    {
        $this->dispatch(new UpdateTaskCommand(
            taskId: (string) $this->getFixture('task_done')->getId(),
            clearFields: ['completedAt'],
        ));

        $task = $this->reload();
        self::assertNull($task->getCompletedAt());
        self::assertFalse($task->isDone());
        self::assertSame('Some notes', $task->getDescription());
        self::assertNotNull($task->getDueDate());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testClearingDescriptionAndDueDate(): void
    {
        $this->dispatch(new UpdateTaskCommand(
            taskId: (string) $this->getFixture('task_done')->getId(),
            clearFields: ['description', 'dueDate'],
        ));

        $task = $this->reload();
        self::assertNull($task->getDescription());
        self::assertNull($task->getDueDate());
        self::assertTrue($task->isDone());
    }

    public function testNullFieldsWithoutClearAreLeftUntouched(): void
    {
        $this->dispatch(new UpdateTaskCommand(
            taskId: (string) $this->getFixture('task_done')->getId(),
            title: 'Renamed',
        ));

        $task = $this->reload();
        self::assertSame('Renamed', $task->getTitle());
        self::assertSame('Some notes', $task->getDescription());
        self::assertNotNull($task->getDueDate());
        self::assertTrue($task->isDone());
    }

    /** The scheduled day is Google's `due` (MAG-822): changing it alone patches Google with it. */
    public function testScheduledDateChangePushes(): void
    {
        $google = $this->googleTask();
        $google->expects(self::once())->method('patchTask')
            ->with(self::anything(), 'list-1', 'g-moving', self::callback(
                fn (GoogleTask $t) => '2026-11-22T00:00:00.000Z' === $t->getDue() && null === $t->getTitle(),
            ))
            ->willReturn(new GoogleTask(['id' => 'g-moving']));

        $this->dispatch(new UpdateTaskCommand(
            taskId: (string) $this->getFixture('moving')->getId(),
            scheduledDate: new \DateTimeImmutable('2026-11-22'),
        ));
    }

    /** Google keeps no time of day: the moment or the time alone pushes nothing. */
    public function testMomentOrTimeOnlyDoesNotPush(): void
    {
        $google = $this->googleTask();
        $id = (string) $this->getFixture('moving')->getId();
        $google->expects(self::once())->method('patchTask')->willReturn(new GoogleTask(['id' => 'g-moving']));
        $this->dispatch(new UpdateTaskCommand(taskId: $id, scheduledDate: new \DateTimeImmutable('2026-11-22')));

        $this->dispatch(new UpdateTaskCommand(taskId: $id, scheduledMoment: 'evening'));
        $this->dispatch(new UpdateTaskCommand(taskId: $id, scheduledMoment: 'time', scheduledTime: '18:00'));
        $this->dispatch(new UpdateTaskCommand(taskId: $id, scheduledTime: '19:00', reminders: [15]));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('19:00', $em->getRepository(Task::class)->find($id)?->getScheduledTime());
    }

    /** The deadline is Maggie's own: setting or clearing it pushes nothing. */
    public function testDueDateOnlyDoesNotPush(): void
    {
        $google = $this->googleTask();
        $id = (string) $this->getFixture('moving')->getId();
        $google->expects(self::never())->method('patchTask');
        $google->expects(self::never())->method('updateTask');

        $this->dispatch(new UpdateTaskCommand(taskId: $id, dueDate: new \DateTimeImmutable('2026-11-30')));
        $this->dispatch(new UpdateTaskCommand(taskId: $id, clearFields: ['dueDate']));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Task::class)->find($id)?->getDueDate());
    }

    public function testUnknownTaskFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Task not found');

        $this->dispatch(new UpdateTaskCommand(taskId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['completedAt']));
    }

    /** A task synced with Google, and Google's API mocked. */
    private function googleTask(): GoogleTasksApiClient&MockObject
    {
        $this->loadFixtures(\dirname(__DIR__).'/Api/fixtures/TaskGoogleMoveApiTest.yaml');
        $google = $this->createMock(GoogleTasksApiClient::class);
        self::getContainer()->set(GoogleTasksApiClient::class, $google);

        return $google;
    }
}
