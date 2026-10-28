<?php

namespace Maggie\Calendar\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Exception as GoogleServiceException;
use Google\Service\Tasks\Task as GoogleTask;
use Google\Service\Tasks\TaskLinks;
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

    /**
     * A task with notes, two subtasks and a link arrives whole (MAG-404),
     * the subtasks in Google's order — listed here before their parent, and
     * in reverse, as Google is free to.
     */
    public function testPullBringsNotesSubtasksInGooglesOrderAndLinks(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();

        $second = $this->googleTask('g-second', 'Changer d\'adresse', parent: 'g-moving', position: '00000000000000000001');
        $first = $this->googleTask('g-first', 'Réserver le camion', parent: 'g-moving', position: '00000000000000000000');
        $moving = $this->googleTask('g-moving', 'Préparer le déménagement', position: '00000000000000000003');
        $moving->setNotes('Les cartons sont à la cave');
        $link = new TaskLinks();
        $link->setType('email');
        $link->setDescription('Le mail du déménageur');
        $link->setLink('https://mail.google.com/mail/#all/abc');
        $moving->setLinks([$link]);

        $this->apiClient->method('listTasks')->willReturn([$second, $moving, $first]);
        $this->taskRepository->method('findBy')->willReturn([]);
        $persisted = [];
        $this->entityManager->method('persist')->willReturnCallback(function (Task $task) use (&$persisted): void {
            $persisted[$task->getGoogleTaskId()] = $task;
        });

        $this->createService()->pullFromGoogle($user);

        self::assertCount(3, $persisted);
        $parent = $persisted['g-moving'];
        self::assertNull($parent->getParent());
        self::assertSame('Les cartons sont à la cave', $parent->getDescription());
        self::assertSame([[
            'type' => 'email',
            'description' => 'Le mail du déménageur',
            'link' => 'https://mail.google.com/mail/#all/abc',
        ]], $parent->getLinks());

        $subtasks = array_values(array_filter($persisted, static fn (Task $task): bool => $task->getParent() === $parent));
        usort($subtasks, static fn (Task $a, Task $b): int => strcmp((string) $a->getPosition(), (string) $b->getPosition()));
        self::assertSame(['Réserver le camion', 'Changer d\'adresse'], array_map(static fn (Task $task) => $task->getTitle(), $subtasks));
    }

    /** A subtask moved to the top level on Google leaves its parent in Maggie too. */
    public function testPullDetachesASubtaskGoogleMovedToTheTopLevel(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();

        $parent = $this->localTask($user, 'g-parent');
        $child = $this->localTask($user, 'g-child')->setParent($parent);
        $child->setGoogleTaskUpdatedAt(new \DateTimeImmutable('2026-03-20T10:00:00Z'));

        $moved = $this->googleTask('g-child', 'Child', position: '00000000000000000009');
        $moved->setUpdated('2026-03-21T10:00:00Z');
        $unchanged = $this->googleTask('g-parent', 'Parent');
        $unchanged->setUpdated('2026-03-01T10:00:00Z');
        $parent->setGoogleTaskUpdatedAt(new \DateTimeImmutable('2026-03-01T10:00:00Z'));

        $this->apiClient->method('listTasks')->willReturn([$moved, $unchanged]);
        $this->taskRepository->method('findBy')->willReturn([$parent, $child]);

        $this->createService()->pullFromGoogle($user);

        self::assertNull($child->getParent());
        self::assertSame('00000000000000000009', $child->getPosition());
    }

    /** Added in Maggie, a subtask is created under its parent on Google, after the sibling before it. */
    public function testPushingANewSubtaskInsertsItUnderItsParentAfterItsPreviousSibling(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $parent = $this->localTask($user, 'g-parent');
        $sibling = $this->localTask($user, 'g-sibling')->setParent($parent);
        $task = (new Task())->setUser($user)->setTitle('New subtask')->setParent($parent)->setPosition('00000000000000000001');

        $this->taskRepository->method('findSiblings')->with($user, $parent)->willReturn([$sibling, $task]);
        $created = $this->googleTask('g-new', 'New subtask', parent: 'g-parent', position: '00000000000000000042');
        $this->apiClient->expects(self::once())
            ->method('insertTask')
            ->with($user, 'task-list-1', self::isInstanceOf(GoogleTask::class), 'g-parent', 'g-sibling')
            ->willReturn($created);

        $this->createService()->pushTaskToGoogle($task);

        self::assertSame('g-new', $task->getGoogleTaskId());
        self::assertSame('00000000000000000042', $task->getPosition(), 'Google\'s position replaces Maggie\'s');
    }

    /** Both made while Google was away: the parent goes first, so the subtask lands under it. */
    public function testPushingASubtaskPushesItsParentFirst(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $parent = (new Task())->setUser($user)->setTitle('Parent');
        $task = (new Task())->setUser($user)->setTitle('Child')->setParent($parent);

        $this->taskRepository->method('findSiblings')->willReturnCallback(
            static fn (User $u, ?Task $p): array => null === $p ? [$parent] : [$task],
        );
        $calls = [];
        $this->apiClient->method('insertTask')->willReturnCallback(
            function (User $u, string $list, GoogleTask $body, ?string $googleParent) use (&$calls): GoogleTask {
                $calls[] = [$body->getTitle(), $googleParent];

                return $this->googleTask('g-'.strtolower($body->getTitle()), $body->getTitle());
            },
        );

        $this->createService()->pushTaskToGoogle($task);

        self::assertSame([['Parent', null], ['Child', 'g-parent']], $calls);
    }

    /** Reordered in Maggie, a subtask is moved on Google — `move`, which a patch cannot do. */
    public function testReorderingASubtaskMovesItOnGoogle(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $parent = $this->localTask($user, 'g-parent');
        $first = $this->localTask($user, 'g-first')->setParent($parent);
        $task = $this->localTask($user, 'g-task')->setParent($parent);

        $this->taskRepository->method('findSiblings')->willReturn([$first, $task]);
        $this->apiClient->expects(self::once())
            ->method('moveTask')
            ->with($user, 'task-list-1', 'g-task', 'g-parent', 'g-first')
            ->willReturn($this->googleTask('g-task', 'Task', parent: 'g-parent', position: '00000000000000000007'));
        $this->apiClient->expects(self::never())->method('patchTask');

        $this->createService()->pushTaskToGoogle($task, ['position']);

        self::assertSame('00000000000000000007', $task->getPosition());
    }

    /** Moved first and renamed: the move, then a patch that carries no position. */
    public function testAMoveAndAnEditAreTwoCalls(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $task = $this->localTask($user, 'g-task');

        $this->taskRepository->method('findSiblings')->willReturn([$task]);
        $this->apiClient->expects(self::once())
            ->method('moveTask')
            ->with($user, 'task-list-1', 'g-task', null, null)
            ->willReturn($this->googleTask('g-task', 'Task'));
        $this->apiClient->expects(self::once())
            ->method('patchTask')
            ->with($user, 'task-list-1', 'g-task', self::callback(static fn (GoogleTask $t): bool => 'Renamed' === $t->getTitle()))
            ->willReturn($this->googleTask('g-task', 'Renamed'));

        $this->createService()->pushTaskToGoogle($task->setTitle('Renamed'), ['title', 'position']);
    }

    /**
     * MAG-678: the sibling it should follow was deleted on Google and Maggie has
     * not pulled that yet — Google answers « Previous task id not found ». The
     * task is created first among its siblings rather than lost to a failing push.
     */
    public function testInsertFallsBackToFirstWhenThePreviousSiblingIsGoneFromGoogle(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $sibling = $this->localTask($user, 'g-gone');
        $task = (new Task())->setUser($user)->setTitle('New')->setPosition('00000000000000000001');

        $this->taskRepository->method('findSiblings')->willReturn([$sibling, $task]);
        $calls = [];
        $this->apiClient->method('insertTask')->willReturnCallback(
            function (User $u, string $list, GoogleTask $body, ?string $parent, ?string $previous) use (&$calls): GoogleTask {
                $calls[] = $previous;
                if (null !== $previous) {
                    throw new GoogleServiceException('{"error":{"code":404,"message":"Previous task id not found"}}', 404);
                }

                return $this->googleTask('g-new', 'New', position: '00000000000000000042');
            },
        );

        $this->createService()->pushTaskToGoogle($task);

        self::assertSame(['g-gone', null], $calls);
        self::assertSame('g-new', $task->getGoogleTaskId());
    }

    public function testMoveFallsBackToFirstWhenThePreviousSiblingIsGoneFromGoogle(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $first = $this->localTask($user, 'g-gone');
        $task = $this->localTask($user, 'g-task');

        $this->taskRepository->method('findSiblings')->willReturn([$first, $task]);
        $calls = [];
        $this->apiClient->method('moveTask')->willReturnCallback(
            function (User $u, string $list, string $id, ?string $parent, ?string $previous) use (&$calls): GoogleTask {
                $calls[] = $previous;
                if (null !== $previous) {
                    throw new GoogleServiceException('Previous task id not found', 404);
                }

                return $this->googleTask('g-task', 'Task', position: '00000000000000000007');
            },
        );

        $this->createService()->pushTaskToGoogle($task, ['position']);

        self::assertSame(['g-gone', null], $calls);
        self::assertSame('00000000000000000007', $task->getPosition());
    }

    /** A 404 with nothing to fall back on (the list itself is gone) is still a failure. */
    public function testInsertStillFailsOn404WhenNoPreviousSiblingWasSent(): void
    {
        $user = $this->createGoogleUser();
        $this->taskMapper = new GoogleTaskMapper();
        $task = (new Task())->setUser($user)->setTitle('New');

        $this->taskRepository->method('findSiblings')->willReturn([$task]);
        $this->apiClient->expects(self::once())->method('insertTask')
            ->willThrowException(new GoogleServiceException('Requested entity was not found.', 404));

        $this->expectException(GoogleServiceException::class);

        $this->createService()->pushTaskToGoogle($task);
    }

    private function googleTask(string $id, string $title, ?string $parent = null, ?string $position = null): GoogleTask
    {
        $googleTask = new GoogleTask();
        $googleTask->setId($id);
        $googleTask->setTitle($title);
        $googleTask->setStatus('needsAction');
        $googleTask->setParent($parent);
        $googleTask->setPosition($position);

        return $googleTask;
    }

    private function localTask(User $user, string $googleTaskId): Task
    {
        $task = new Task();
        $task->setUser($user);
        $task->setTitle($googleTaskId);
        $task->setGoogleTaskId($googleTaskId);
        $task->setGoogleTaskListId('task-list-1');

        return $task;
    }
}
