<?php

namespace App\Tests\Calendar\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Mcp\Tool\GetTasksTool;
use Maggie\Calendar\Repository\TaskRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class GetTasksToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    private function getTool(): GetTasksTool
    {
        return new GetTasksTool(self::getContainer()->get(TaskRepository::class));
    }

    public function testReturnsPendingTasksByDefault(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');

        $result = $this->getTool()('pending');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('pending', $data['status']);
        $names = array_map(fn($t) => $t['name'], $data['tasks']);
        self::assertContains('Pending task', $names);
        self::assertNotContains('Done task', $names);
    }

    public function testReturnsOverdueTasks(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');

        $result = $this->getTool()('overdue');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('overdue', $data['status']);
        self::assertSame(1, $data['count']);
        self::assertSame('Overdue task', $data['tasks'][0]['name']);
    }

    public function testReturnsDoneTasks(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');

        $result = $this->getTool()('done');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('done', $data['status']);
        self::assertSame(1, $data['count']);
        self::assertSame('Done task', $data['tasks'][0]['name']);
    }

    public function testOutputContainsAllExpectedFields(): void
    {
        $this->loadFixtures('GetTasksToolTest.yaml');

        $result = $this->getTool()('pending');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        // Find the task_future fixture (has all fields populated)
        $fullTask = null;
        foreach ($data['tasks'] as $task) {
            if ($task['name'] === 'Future task') {
                $fullTask = $task;
                break;
            }
        }
        self::assertNotNull($fullTask, 'Future task not found in pending results');

        self::assertArrayHasKey('id', $fullTask);
        self::assertArrayHasKey('name', $fullTask);
        self::assertArrayHasKey('description', $fullTask);
        self::assertArrayHasKey('priority', $fullTask);
        self::assertArrayHasKey('criticality', $fullTask);
        self::assertArrayHasKey('dueDate', $fullTask);
        self::assertArrayHasKey('isDone', $fullTask);
        self::assertArrayHasKey('doneDate', $fullTask);
    }
}
