<?php

namespace App\Tests\Calendar\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Mcp\Tool\CreateTaskTool;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class CreateTaskToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
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
}
