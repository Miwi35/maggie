<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\UpdateTaskCommand;
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

    public function testUnknownTaskFails(): void
    {
        $this->expectException(\Throwable::class);
        $this->expectExceptionMessage('Task not found');

        $this->dispatch(new UpdateTaskCommand(taskId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clearFields: ['completedAt']));
    }
}
