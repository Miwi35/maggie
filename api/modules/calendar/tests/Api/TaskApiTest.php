<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class TaskApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @param array<string, mixed> $payload */
    private function patchTask(Task $task, array $payload): void
    {
        $this->client->request('PATCH', '/api/tasks/' . $task->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Task $task): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Task::class)->find($task->getId());
    }

    private function loadDoneTask(): Task
    {
        $this->loadFixtures('TaskApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('task_done');
    }

    public function testPatchTasksRequiresAuthentication(): void
    {
        $task = $this->loadDoneTask();

        $this->client->request('PATCH', '/api/tasks/' . $task->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['completedAt' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullCompletedAtReopensTheTask(): void
    {
        $task = $this->loadDoneTask();

        $this->patchTask($task, ['completedAt' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($task);
        self::assertNull($reloaded->getCompletedAt());
        self::assertFalse($reloaded->isDone());
        self::assertSame('Some notes', $reloaded->getDescription(), 'Fields left out of the payload are untouched');
        self::assertNotNull($reloaded->getDueDate());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testPatchWithNullDescriptionAndDueDateClearsThem(): void
    {
        $task = $this->loadDoneTask();

        $this->patchTask($task, ['description' => null, 'dueDate' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($task);
        self::assertNull($reloaded->getDescription());
        self::assertNull($reloaded->getDueDate());
        self::assertTrue($reloaded->isDone(), 'completedAt was not in the payload');
    }

    public function testPatchWithInvalidTitleIsRejected(): void
    {
        $task = $this->loadDoneTask();

        $this->patchTask($task, ['title' => '']);

        self::assertResponseStatusCodeSame(422);
        self::assertSame('Done task', $this->reload($task)->getTitle());
    }
}
