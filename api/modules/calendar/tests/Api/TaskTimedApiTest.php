<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** A task done with a timer, for its estimated minutes, over HTTP (MAG-766). */
final class TaskTimedApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('TaskApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testATimedTaskNeedsAUser(): void
    {
        $this->client->request('POST', '/api/tasks', [], [], ['CONTENT_TYPE' => 'application/ld+json'], json_encode(['title' => 'Méditer', 'timed' => true], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->em()->getRepository(Task::class)->findOneBy(['title' => 'Méditer']));
    }

    public function testATaskIsCreatedTimedForItsEstimatedMinutes(): void
    {
        $this->post(['title' => 'Méditer', 'estimatedMinutes' => 20, 'timed' => true]);

        self::assertResponseStatusCodeSame(201);
        $json = $this->json();
        self::assertTrue($json['timed']);
        self::assertSame(20, $json['estimatedMinutes']);
        $task = $this->reload((string) $json['id']);
        self::assertTrue($task->isTimed());
        self::assertSame(20, $task->getEstimatedMinutes());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testATaskIsNotTimedByDefault(): void
    {
        $this->post(['title' => 'Appeler le garage', 'estimatedMinutes' => 10]);

        self::assertResponseStatusCodeSame(201);
        self::assertFalse($this->json()['timed']);
        self::assertFalse($this->reload((string) $this->json()['id'])->isTimed());
    }

    public function testPatchTurnsTheTimerOnThenOff(): void
    {
        $task = $this->getFixture('task_moving');

        $this->patch($task, ['timed' => true]);
        self::assertResponseIsSuccessful();
        self::assertTrue($this->reload((string) $task->getId())->isTimed());
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->patch($task, ['timed' => false]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload((string) $task->getId());
        self::assertFalse($reloaded->isTimed());
        self::assertSame('Les cartons sont à la cave', $reloaded->getDescription(), 'the rest untouched');
        $this->assertMercureUpdatePublished('/tasks/'.$task->getId());
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testTheItemAndTheCollectionReadTimed(): void
    {
        $task = $this->getFixture('task_moving');
        $this->patch($task, ['timed' => true, 'estimatedMinutes' => 30]);
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/tasks/'.$task->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        self::assertTrue($this->json()['timed']);

        $this->client->request('GET', '/api/tasks', [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        $timed = array_column($this->json()['member'], 'timed', 'title');
        self::assertTrue($timed['Préparer le déménagement']);
        self::assertFalse($timed['Done task']);
    }

    public function testANonBooleanTimedIsRefused(): void
    {
        $this->post(['title' => 'Refusée', 'timed' => 'oui']);
        self::assertResponseStatusCodeSame(400);

        $task = $this->getFixture('task_moving');
        $this->patch($task, ['timed' => 'oui']);
        self::assertResponseStatusCodeSame(400);

        self::assertNull($this->em()->getRepository(Task::class)->findOneBy(['title' => 'Refusée']));
        self::assertFalse($this->reload((string) $task->getId())->isTimed());
        $this->assertMercureUpdateCount(0);
    }

    /** @param array<string, mixed> $payload */
    private function post(array $payload): void
    {
        $this->client->request('POST', '/api/tasks', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @param array<string, mixed> $payload */
    private function patch(Task $task, array $payload): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function reload(string $id): Task
    {
        $task = $this->em()->find(Task::class, $id);
        self::assertInstanceOf(Task::class, $task);

        return $task;
    }
}
