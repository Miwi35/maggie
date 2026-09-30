<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class UpdateTaskToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('UpdateTaskToolTest.yaml');
        $this->loginFixtureUser();
    }

    private function tool(): UpdateTaskTool
    {
        return self::getContainer()->get(UpdateTaskTool::class);
    }

    private function id(): string
    {
        return (string) $this->getFixture('task_done')->getId();
    }

    private function reload(): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Task::class)->find($this->getFixture('task_done')->getId());
    }

    public function testDoneFalseReopensTheTask(): void
    {
        $data = json_decode(($this->tool())($this->id(), done: false), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertFalse($data['task']['isDone']);
        self::assertNull($this->reload()->getCompletedAt());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testDoneTrueCompletesAnOpenTask(): void
    {
        ($this->tool())($this->id(), done: false);
        $data = json_decode(($this->tool())($this->id(), done: true), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['task']['isDone']);
        self::assertNotNull($this->reload()->getCompletedAt());
    }

    public function testClearEmptiesOptionalFields(): void
    {
        $data = json_decode(
            ($this->tool())($this->id(), clear: ['description', 'dueDate', 'title']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        $task = $this->reload();
        self::assertNull($task->getDescription());
        self::assertNull($task->getDueDate());
        self::assertSame('Done task', $task->getTitle(), 'Required fields cannot be cleared');
        self::assertTrue($task->isDone());
    }

    public function testUnknownTaskReturnsAnError(): void
    {
        $data = json_decode(($this->tool())('01ARZ3NDEKTSV4RRFFQ69G5FAV', done: false), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }
}
