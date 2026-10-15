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
        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Task $task, ?string $id = null): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Task::class)->find($id ?? $task->getId());
    }

    private function loadDoneTask(): Task
    {
        $this->loadFixtures('TaskApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('task_done');
    }

    /** The admin sends the whole record back, its `id` an IRI (MAG-480). */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $entity = $this->loadDoneTask();

        $this->client->request('GET', '/api/tasks/'.$entity->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $record = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $record['id'] = $record['@id'];
        $record['originId'] = (string) $entity->getId();
        $record['title'] = 'Renommé';

        $this->patchTask($entity, $record);

        self::assertResponseIsSuccessful();
        self::assertSame('Renommé', $this->reload($entity)->getTitle());
    }

    public function testPatchTasksRequiresAuthentication(): void
    {
        $task = $this->loadDoneTask();

        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], [
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

    /** @param array<string, mixed> $payload */
    private function postTask(array $payload, bool $authenticated = true): void
    {
        $this->client->request('POST', '/api/tasks', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function loadMoving(): Task
    {
        $this->loadFixtures('TaskApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('task_moving');
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testPostTasksRequiresAuthentication(): void
    {
        $this->loadMoving();

        $this->postTask(['title' => 'Anonymous'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** A subtask added in Maggie: under its parent, after its siblings (MAG-404). */
    public function testPostASubtaskPutsItLastUnderItsParent(): void
    {
        $moving = $this->loadMoving();

        $this->postTask(['title' => 'Résilier la box', 'parent' => '/api/tasks/'.$moving->getId(), 'dueDate' => '2026-04-01']);

        self::assertResponseStatusCodeSame(201);
        $body = $this->json();
        self::assertSame('/api/tasks/'.$moving->getId(), $body['parent']);
        self::assertSame('2026-04-01', $body['dueDate']);
        $created = $this->reload((new Task())->setTitle('x'), $body['id']);
        self::assertTrue($created->getParent()?->getId()->equals($moving->getId()));
        self::assertSame('00000000000000000002', $created->getPosition(), 'after the two subtasks already there');
        self::assertSame('2026-04-01', $created->getDueDate()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished('/api/tasks/'.$body['id']);
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $body['id']);
    }

    public function testASubtaskOfASubtaskIsRefused(): void
    {
        $this->loadMoving();
        $subtask = $this->getFixture('subtask_first');

        $this->postTask(['title' => 'Too deep', 'parent' => '/api/tasks/'.$subtask->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('A subtask cannot have subtasks', (string) $this->client->getResponse()->getContent());
    }

    public function testATaskWithSubtasksCannotBecomeASubtask(): void
    {
        $moving = $this->loadMoving();
        $done = $this->getFixture('task_done');

        $this->patchTask($moving, ['parent' => '/api/tasks/'.$done->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('A task with subtasks cannot become a subtask', (string) $this->client->getResponse()->getContent());
        self::assertNull($this->reload($moving)->getParent());
    }

    public function testAnotherUsersTaskCannotBeAParent(): void
    {
        $this->loadMoving();
        $foreign = $this->getFixture('other_users_task');

        $this->postTask(['title' => 'Sneaky', 'parent' => '/api/tasks/'.$foreign->getId()]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testADueDateThatIsNotADayIsRefused(): void
    {
        $this->loadMoving();

        $this->postTask(['title' => 'Bad day', 'dueDate' => '2026-13-45']);

        self::assertResponseStatusCodeSame(400);
    }

    /** Reordered in Maggie: the position written is the one kept, and the screens hear of it. */
    public function testPatchMovesASubtaskAndDetachesIt(): void
    {
        $this->loadMoving();
        $second = $this->getFixture('subtask_second');

        $this->patchTask($second, ['position' => '00000000000000000000']);
        self::assertResponseIsSuccessful();
        self::assertSame('00000000000000000000', $this->reload($second)->getPosition());
        $this->assertMercureUpdatePublished('/api/tasks/'.$second->getId());
        $this->assertElasticsearchIndexDispatchedFor(Task::class, (string) $second->getId());

        $this->patchTask($second, ['parent' => null]);
        self::assertResponseIsSuccessful();
        $detached = $this->reload($second);
        self::assertNull($detached->getParent());
        self::assertNotNull($detached->getPosition(), 'a task leaving its parent goes last among the top-level tasks');
    }

    public function testTheItemRendersParentPositionAndLinks(): void
    {
        $moving = $this->loadMoving();
        $first = $this->getFixture('subtask_first');

        $this->client->request('GET', '/api/tasks/'.$moving->getId(), [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $body = $this->json();
        self::assertSame('Les cartons sont à la cave', $body['description']);
        self::assertSame('00000000000000000005', $body['position']);
        self::assertSame([['type' => 'email', 'description' => 'Le mail du déménageur', 'link' => 'https://mail.google.com/mail/#all/abc']], $body['links']);

        $this->client->request('GET', '/api/tasks?parent=/api/tasks/'.$moving->getId().'&order[position]=asc', [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $members = $this->json()['member'];
        self::assertSame(['Réserver le camion', "Changer d'adresse"], array_column($members, 'title'));
        self::assertSame('/api/tasks/'.$moving->getId(), $members[0]['parent']);
        self::assertSame((string) $first->getId(), $members[0]['id']);
    }

    /** As on Google, the subtasks go with their parent — out of the index and the screens too. */
    public function testDeletingATaskDeletesItsSubtasks(): void
    {
        $moving = $this->loadMoving();
        $first = $this->getFixture('subtask_first');

        $this->client->request('DELETE', '/api/tasks/'.$moving->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(0, $em->getRepository(Task::class)->count(['parent' => $moving->getId()]));
        self::assertNull($em->getRepository(Task::class)->find($moving->getId()));
        $this->assertMercureDeletePublished('/api/tasks/'.$first->getId());
        $this->assertElasticsearchDeleteDispatched('tasks');
    }
}
