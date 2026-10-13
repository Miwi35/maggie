<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use PHPUnit\Framework\MockObject\MockObject;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * A subtask reordered or added in Maggie lands in the same place on Google
 * (MAG-404), through the real handlers and sync — Google stubbed at its client.
 */
class TaskGoogleMoveApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;
    private GoogleTasksApiClient&MockObject $google;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // The stub lives in the test container: a reboot would build the real client.
        $this->client->disableReboot();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('TaskGoogleMoveApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('google_user'));

        $this->google = $this->createMock(GoogleTasksApiClient::class);
        self::getContainer()->set(GoogleTasksApiClient::class, $this->google);
    }

    /**
     * Moving the second subtask up is one write: it takes the first one's
     * place, the first one takes its own, and Google moves it to the top —
     * where two writes, each pushed to Google, left the order as it was.
     */
    public function testMovingASubtaskUpMovesItFirstOnGoogle(): void
    {
        $second = $this->getFixture('second');
        $first = $this->getFixture('first');
        $this->google->expects(self::once())
            ->method('moveTask')
            ->with(self::anything(), 'list-1', 'g-second', 'g-moving', null)
            ->willReturn($this->googleAnswer('g-second', '00000000000000000000'));
        $this->google->expects(self::never())->method('patchTask');

        $this->patch($second, ['position' => '00000000000000000000']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Changer d\'adresse', 'Réserver le camion'], $this->subtaskTitles());
        self::assertSame('00000000000000000001', $this->reload($first)->getPosition());
        $this->assertMercureUpdatePublished('/api/tasks/'.$first->getId());
        $this->assertElasticsearchIndexDispatchedFor(Task::class, (string) $first->getId());
    }

    public function testMovingASubtaskDownPutsItAfterItsNeighbourOnGoogle(): void
    {
        $first = $this->getFixture('first');
        $this->google->expects(self::once())
            ->method('moveTask')
            ->with(self::anything(), 'list-1', 'g-first', 'g-moving', 'g-second')
            ->willReturn($this->googleAnswer('g-first', '00000000000000000002'));

        $this->patch($first, ['position' => '00000000000000000001']);

        self::assertResponseIsSuccessful();
        self::assertSame(['Changer d\'adresse', 'Réserver le camion'], $this->subtaskTitles());
    }

    /** Added in Maggie, a subtask is created under its parent on Google, after the last one. */
    public function testAddingASubtaskCreatesItUnderItsParentOnGoogle(): void
    {
        $moving = $this->getFixture('moving');
        $this->google->expects(self::once())
            ->method('insertTask')
            ->with(self::anything(), 'list-1', self::isInstanceOf(GoogleTask::class), 'g-moving', 'g-second')
            ->willReturn($this->googleAnswer('g-third', '00000000000000000002'));

        $this->client->request('POST', '/api/tasks', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['title' => 'Résilier la box', 'parent' => '/api/tasks/'.$moving->getId()], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        self::assertSame(['Réserver le camion', 'Changer d\'adresse', 'Résilier la box'], $this->subtaskTitles());
    }

    /** @param array<string, mixed> $payload */
    private function patch(Task $task, array $payload): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function googleAnswer(string $id, string $position): GoogleTask
    {
        $answer = new GoogleTask();
        $answer->setId($id);
        $answer->setPosition($position);
        $answer->setEtag('"etag"');
        $answer->setUpdated('2026-03-20T10:00:00.000Z');

        return $answer;
    }

    private function reload(Task $task): Task
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Task::class)->find($task->getId());
    }

    /** @return list<string> the subtasks of « Préparer le déménagement », in Maggie's order */
    private function subtaskTitles(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $parent = $em->getRepository(Task::class)->find($this->getFixture('moving')->getId());

        return array_map(
            static fn (Task $task): string => $task->getTitle(),
            $em->getRepository(Task::class)->findSiblings($parent->getUser(), $parent),
        );
    }
}
