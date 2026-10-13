<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\UpdateTaskTool;
use Maggie\Core\Mcp\MissingMcpUserException;
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

    public function testWithoutAUserNothingChanges(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        $data = json_decode(($this->tool())($this->id(), title: 'Hijacked'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame('Done task', $this->reload()->getTitle());
    }

    public function testDueDateIsWrittenAsADay(): void
    {
        $data = json_decode(($this->tool())($this->id(), dueDate: '2026-05-02'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('2026-05-02', $data['task']['dueDate']);
        self::assertSame('2026-05-02', $this->reload()->getDueDate()?->format('Y-m-d'));
    }

    public function testADueDateThatIsNotADayIsRefused(): void
    {
        $data = json_decode(($this->tool())($this->id(), dueDate: 'demain'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    /** Made a subtask, then a top-level task again (MAG-404). */
    public function testParentIdAttachesAndClearParentDetaches(): void
    {
        $parentId = (string) $this->getFixture('task_parent')->getId();

        $data = json_decode(($this->tool())($this->id(), parentId: $parentId), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame($parentId, $data['task']['parentId']);
        $task = $this->reload();
        self::assertSame($parentId, (string) $task->getParent()?->getId());
        self::assertSame('00000000000000000001', $task->getPosition(), 'last, after the subtask already there');
        $this->assertMercureUpdatePublished('/api/tasks/'.$this->id());
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $this->id());

        ($this->tool())($this->id(), clear: ['parent']);
        self::assertNull($this->reload()->getParent());
    }

    public function testASubtaskCannotTakeASubtask(): void
    {
        $childId = (string) $this->getFixture('task_child')->getId();

        $data = json_decode(($this->tool())($this->id(), parentId: $childId), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('A subtask cannot have subtasks', $data['error']);
        self::assertNull($this->reload()->getParent());
    }

    public function testPositionReordersASubtask(): void
    {
        $child = $this->getFixture('task_child');

        ($this->tool())((string) $child->getId(), position: '00000000000000000009');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame('00000000000000000009', $em->getRepository(Task::class)->find($child->getId())->getPosition());
    }

    /** Another user's task is out of reach, as if it did not exist. */
    public function testAnotherUsersTaskIsNotFound(): void
    {
        $other = new \Maggie\Core\Entity\User();
        $other->setEmail('other@example.com');
        $other->setGoogleId('google-other');
        $other->setName('Other');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($other);
        $em->flush();
        $this->loginUser($other);

        $data = json_decode(($this->tool())($this->id(), title: 'Hijacked'), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('Task not found', $data['error']);
        self::assertSame('Done task', $this->reload()->getTitle());
    }
}
