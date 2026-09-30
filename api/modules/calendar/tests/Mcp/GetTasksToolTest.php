<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetTasksToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function getTool(): GetTasksTool
    {
        return self::getContainer()->get(GetTasksTool::class);
    }

    public function testReturnsPendingTasksByDefault(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()('pending');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('pending', $data['status']);
        $titles = array_map(fn ($t) => $t['title'], $data['tasks']);
        self::assertContains('Pending task', $titles);
        self::assertNotContains('Done task', $titles);
    }

    public function testReturnsOverdueTasks(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()('overdue');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('overdue', $data['status']);
        self::assertSame(1, $data['count']);
        self::assertSame('Overdue task', $data['tasks'][0]['title']);
    }

    public function testReturnsDoneTasks(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()('done');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('done', $data['status']);
        self::assertSame(1, $data['count']);
        self::assertSame('Done task', $data['tasks'][0]['title']);
    }

    public function testOutputContainsAllExpectedFields(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');
        $this->loginFixtureUser();

        $result = $this->getTool()('pending');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        // Find the task_future fixture (has all fields populated)
        $fullTask = null;
        foreach ($data['tasks'] as $task) {
            if ('Future task' === $task['title']) {
                $fullTask = $task;
                break;
            }
        }
        self::assertNotNull($fullTask, 'Future task not found in pending results');

        self::assertArrayHasKey('id', $fullTask);
        self::assertArrayHasKey('title', $fullTask);
        self::assertArrayHasKey('description', $fullTask);
        self::assertArrayHasKey('priority', $fullTask);
        self::assertArrayHasKey('criticality', $fullTask);
        self::assertArrayHasKey('dueDate', $fullTask);
        self::assertArrayHasKey('isDone', $fullTask);
        self::assertArrayHasKey('completedAt', $fullTask);
    }
}
