<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CreateTaskToolTest extends KernelTestCase
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
        $this->purgeDatabase();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $user = new User();
        $user->setEmail('mcp-test@example.com');
        $user->setGoogleId('google-mcp-test');
        $user->setName('MCP Test User');
        $em->persist($user);
        $em->flush();

        $this->loginUser($user);
    }

    private function getTool(): CreateTaskTool
    {
        return self::getContainer()->get(CreateTaskTool::class);
    }

    public function testCreateTaskPersistsPublishesAndIndexes(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $tool = $this->getTool();

        $result = $tool('Buy groceries', 'Milk, eggs, bread', 'high', 'medium', '2026-03-25');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Buy groceries', $data['task']['title']);
        self::assertSame('high', $data['task']['priority']);
        self::assertSame('medium', $data['task']['criticality']);

        // DB persistence
        $tasks = $em->getRepository(Task::class)->findAll();
        self::assertCount(1, $tasks);
        self::assertSame('Buy groceries', $tasks[0]->getTitle());
        self::assertSame('Milk, eggs, bread', $tasks[0]->getDescription());

        // Mercure publication
        $this->assertMercureUpdatePublished('/tasks/');

        // Elasticsearch indexation
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testCreateTaskUsesDefaults(): void
    {
        $tool = $this->getTool();

        $result = $tool('Simple task');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame('Simple task', $data['task']['title']);
        self::assertSame('medium', $data['task']['priority']);
        self::assertSame('low', $data['task']['criticality']);
        self::assertNull($data['task']['dueDate']);
    }

    public function testCreateTaskWithDueDate(): void
    {
        $tool = $this->getTool();

        $result = $tool('Deadline task', dueDate: '2026-04-01');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertNotNull($data['task']['dueDate']);
        self::assertStringStartsWith('2026-04-01', $data['task']['dueDate']);
    }

    /** A due date is a day (MAG-404): it comes back as it was given, with no time. */
    public function testTheDueDateIsADay(): void
    {
        $data = json_decode(($this->getTool())('Deadline task', dueDate: '2026-04-01'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('2026-04-01', $data['task']['dueDate']);
    }

    public function testADueDateThatIsNotADayIsRefused(): void
    {
        $data = json_decode(($this->getTool())('Bad', dueDate: '2026-13-45'), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('YYYY-MM-DD', $data['error']);
        self::assertSame(0, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Task::class)->count([]));
    }

    public function testParentIdMakesASubtaskPlacedLast(): void
    {
        $tool = $this->getTool();
        $parent = json_decode($tool('Préparer le déménagement'), true, 512, JSON_THROW_ON_ERROR)['task'];
        $tool('Réserver le camion', parentId: $parent['id']);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode($tool('Changer d\'adresse', parentId: $parent['id']), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame($parent['id'], $data['task']['parentId']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(Task::class)->find($data['task']['id']);
        self::assertSame($parent['id'], (string) $stored->getParent()?->getId());
        self::assertSame('00000000000000000001', $stored->getPosition());
        $this->assertMercureUpdatePublished('/api/tasks/'.$data['task']['id']);
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $data['task']['id']);
    }

    public function testASubtaskOfASubtaskIsRefused(): void
    {
        $tool = $this->getTool();
        $parent = json_decode($tool('Parent'), true, 512, JSON_THROW_ON_ERROR)['task'];
        $child = json_decode($tool('Child', parentId: $parent['id']), true, 512, JSON_THROW_ON_ERROR)['task'];

        $data = json_decode($tool('Grandchild', parentId: $child['id']), true, 512, JSON_THROW_ON_ERROR);

        self::assertStringContainsString('A subtask cannot have subtasks', $data['error']);
    }

    public function testWithoutAUserNothingIsCreated(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        $data = json_decode(($this->getTool())('Anonymous'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
    }
}
