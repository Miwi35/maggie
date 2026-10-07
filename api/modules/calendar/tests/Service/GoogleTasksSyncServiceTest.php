<?php

namespace Maggie\Calendar\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\GoogleTaskListSelection;
use Maggie\Calendar\Service\GoogleTaskMapper;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Calendar\Service\GoogleTasksSyncService;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

class GoogleTasksSyncServiceTest extends TestCase
{
    /** @var Update[] */
    private array $publishedUpdates = [];
    private HubInterface $hub;
    private MessageBusInterface $bus;
    /** @var object[] */
    private array $dispatched = [];
    private GoogleTasksApiClient $apiClient;
    private GoogleTaskMapper $taskMapper;
    private TaskRepository $taskRepository;
    private GoogleTaskListSelection $selection;
    private EntityManagerInterface $entityManager;

    protected function setUp(): void
    {
        $this->publishedUpdates = [];
        $this->dispatched = [];
        $this->bus = $this->createMock(MessageBusInterface::class);
        $this->bus->method('dispatch')->willReturnCallback(function (object $message) {
            $this->dispatched[] = $message;

            return new Envelope($message);
        });
        $this->hub = $this->createMock(HubInterface::class);
        $this->hub->method('publish')->willReturnCallback(function (Update $update) {
            $this->publishedUpdates[] = $update;

            return 'urn:uuid:'.new Ulid();
        });

        $this->apiClient = $this->createMock(GoogleTasksApiClient::class);
        $this->taskMapper = $this->createMock(GoogleTaskMapper::class);
        $this->taskRepository = $this->createMock(TaskRepository::class);
        $this->selection = $this->createMock(GoogleTaskListSelection::class);
        $this->entityManager = $this->createMock(EntityManagerInterface::class);
    }

    private function createService(): GoogleTasksSyncService
    {
        return new GoogleTasksSyncService(
            $this->apiClient,
            $this->taskMapper,
            $this->taskRepository,
            $this->selection,
            $this->entityManager,
            $this->hub,
            $this->bus,
            new NullLogger(),
        );
    }

    private function createGoogleUser(): User
    {
        $user = new User();
        $user->setEmail('test@example.com');
        $user->setName('Test User');
        $user->setGoogleId('google-123');
        $user->setGoogleAccessToken('access-token');
        $user->setGoogleRefreshToken('refresh-token');
        $user->setGoogleTaskListId('task-list-1');

        return $user;
    }

    public function testPullSkipsTaskWhenGoogleTimestampNotNewer(): void
    {
        $user = $this->createGoogleUser();

        $googleTask = new GoogleTask();
        $googleTask->setId('g-task-1');
        $googleTask->setTitle('Buy groceries');
        $googleTask->setStatus('needsAction');
        $googleTask->setUpdated('2026-03-20T10:00:00Z');

        $existingTask = new Task();
        $existingTask->setUser($user);
        $existingTask->setTitle('Buy groceries');
        $existingTask->setGoogleTaskId('g-task-1');
        $existingTask->setGoogleTaskListId('task-list-1');
        $existingTask->setGoogleTaskUpdatedAt(new \DateTimeImmutable('2026-03-20T10:00:00Z'));

        $this->apiClient->method('listTasks')->willReturn([$googleTask]);

        $this->taskRepository->method('findBy')->willReturn([$existingTask]);

        // fromGoogle should NOT be called since the task is skipped
        $this->taskMapper->expects(self::never())->method('fromGoogle');

        $service = $this->createService();
        $service->pullFromGoogle($user);

        // No changes published since the task was skipped
        self::assertCount(0, $this->publishedUpdates);
    }

    public function testPullUpdatesTaskWhenGoogleTimestampIsNewer(): void
    {
        $user = $this->createGoogleUser();

        $googleTask = new GoogleTask();
        $googleTask->setId('g-task-1');
        $googleTask->setTitle('Buy groceries - updated');
        $googleTask->setStatus('completed');
        $googleTask->setCompleted('2026-03-20T11:30:00Z');
        $googleTask->setUpdated('2026-03-20T12:00:00Z');

        $existingTask = new Task();
        $existingTask->setUser($user);
        $existingTask->setTitle('Buy groceries');
        $existingTask->setGoogleTaskId('g-task-1');
        $existingTask->setGoogleTaskListId('task-list-1');
        $existingTask->setGoogleTaskUpdatedAt(new \DateTimeImmutable('2026-03-20T10:00:00Z'));

        $updatedTask = new Task();
        $updatedTask->setUser($user);
        $updatedTask->setTitle('Buy groceries - updated');
        $updatedTask->setCompletedAt(new \DateTimeImmutable('2026-03-20T11:30:00Z'));

        $this->apiClient->method('listTasks')->willReturn([$googleTask]);

        $this->taskRepository->method('findBy')->willReturn([$existingTask]);

        // fromGoogle SHOULD be called since Google timestamp is newer
        $this->taskMapper->expects(self::once())
            ->method('fromGoogle')
            ->with($googleTask, $user, $existingTask)
            ->willReturn($updatedTask);

        $this->taskRepository->method('find')
            ->willReturnCallback(fn (string $id) => match ($id) {
                (string) $updatedTask->getId() => $updatedTask,
                default => null,
            });

        $service = $this->createService();
        $service->pullFromGoogle($user);

        self::assertCount(1, $this->publishedUpdates);
        self::assertTrue($this->publishedUpdates[0]->isPrivate());
        self::assertSame(
            '/users/'.$user->getId().'/api/tasks/'.$updatedTask->getId(),
            $this->publishedUpdates[0]->getTopics()[0],
        );
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Buy groceries - updated', $data['title']);
    }

    public function testPushTaskUsesPatchWhenChangedFieldsProvided(): void
    {
        $user = $this->createGoogleUser();

        $task = new Task();
        $task->setUser($user);
        $task->setTitle('Test Task');
        $task->setCompletedAt(new \DateTimeImmutable('2026-03-20T12:00:00Z'));
        $task->setGoogleTaskId('g-task-existing');
        $task->setGoogleTaskListId('task-list-1');

        $resultGoogleTask = new GoogleTask();
        $resultGoogleTask->setEtag('"new-etag"');
        $resultGoogleTask->setUpdated('2026-03-20T12:00:00Z');

        $this->taskMapper = new GoogleTaskMapper();

        $this->apiClient->expects(self::never())->method('updateTask');
        $this->apiClient->expects(self::once())
            ->method('patchTask')
            ->with($user, 'task-list-1', 'g-task-existing', self::callback(
                fn (GoogleTask $t) => 'completed' === $t->getStatus() && null === $t->getTitle(),
            ))
            ->willReturn($resultGoogleTask);

        $service = $this->createService();
        $service->pushTaskToGoogle($task, ['completedAt']);

        self::assertSame('"new-etag"', $task->getGoogleTaskEtag());
    }

    public function testPullRemovesDeletedGoogleTasksFromTheIndex(): void
    {
        $user = $this->createGoogleUser();

        $googleTask = new GoogleTask();
        $googleTask->setId('g-task-1');
        $googleTask->setDeleted(true);

        $deletedOnGoogle = new Task();
        $deletedOnGoogle->setUser($user);
        $deletedOnGoogle->setTitle('Gone on Google');
        $deletedOnGoogle->setGoogleTaskId('g-task-1');
        $deletedOnGoogle->setGoogleTaskListId('task-list-1');

        $missingFromGoogle = new Task();
        $missingFromGoogle->setUser($user);
        $missingFromGoogle->setTitle('Absent from Google');
        $missingFromGoogle->setGoogleTaskId('g-task-2');
        $missingFromGoogle->setGoogleTaskListId('task-list-1');

        $this->apiClient->method('listTasks')->willReturn([$googleTask]);
        $this->taskRepository->method('findBy')->willReturn([$deletedOnGoogle, $missingFromGoogle]);

        $this->createService()->pullFromGoogle($user);

        $deleted = [];
        foreach ($this->dispatched as $message) {
            self::assertInstanceOf(DeleteDocumentCommand::class, $message);
            $deleted[] = [$message->indexName, $message->documentId];
        }
        self::assertEqualsCanonicalizing([
            ['tasks', (string) $deletedOnGoogle->getId()],
            ['tasks', (string) $missingFromGoogle->getId()],
        ], $deleted);
    }

    /**
     * Nothing syncs until the owner has chosen a list — MAG-118.
     *
     * The sync used to call `tasklists.list` and adopt the first answer, so an
     * account with several lists silently synced one of them, and the choice the
     * settings screen offered had nowhere to go.
     */
    public function testPullSyncsNothingUntilAListIsChosen(): void
    {
        $user = $this->createGoogleUser();
        $user->setGoogleTaskListId(null);

        $this->apiClient->expects(self::never())->method('listTaskLists');
        $this->apiClient->expects(self::never())->method('listTasks');

        $this->createService()->pullFromGoogle($user);

        self::assertNull($user->getGoogleTaskListId(), 'no list may be adopted on the owner\'s behalf');
        self::assertSame([], $this->publishedUpdates);
    }

    /**
     * A list deleted on Google is forgotten, not retried forever.
     *
     * Left in place, every sync would fail on a list that is never coming back
     * and the screen would keep claiming to be connected to it.
     */
    public function testPullForgetsAListGoogleNoLongerHas(): void
    {
        $user = $this->createGoogleUser();

        $this->apiClient->method('listTasks')
            ->willThrowException(new GoogleServiceException('Requested entity was not found.', 404));

        $this->selection->expects(self::once())->method('forget')->with($user);

        $this->createService()->pullFromGoogle($user);
    }

    public function testPullRethrowsAGoogleFailureThatIsNotAMissingList(): void
    {
        $user = $this->createGoogleUser();

        $this->apiClient->method('listTasks')
            ->willThrowException(new GoogleServiceException('Backend error', 500));

        // Keeping the choice is the point: a transient failure must not look
        // like a list the owner no longer has.
        $this->selection->expects(self::never())->method('forget');

        $this->expectException(GoogleServiceException::class);

        $this->createService()->pullFromGoogle($user);
    }
}
