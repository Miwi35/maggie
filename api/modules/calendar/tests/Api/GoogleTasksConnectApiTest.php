<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Google\Service\Tasks\TaskList;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\PullTasksFromGoogleCommand;
use Maggie\Calendar\Service\GoogleTasksApiClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\Envelope;

/**
 * Choosing the Google Tasks list to sync with (MAG-118).
 *
 * The settings screen called `task-lists`, `connect-tasks` and
 * `disconnect-tasks`; none of the three existed, and the sync took whichever
 * list Google returned first. The choice is the owner's, and these are the
 * routes it goes through — the screen is one client, Maggie and the mobile app
 * are the others.
 *
 * Google is stubbed at `GoogleTasksApiClient`, so the controller, the selection
 * service and the user it writes to are all real.
 */
class GoogleTasksConnectApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // The stubbed Google client lives in the test container, and a reboot
        // would build a fresh one — the second request of a test would then
        // reach the real API.
        $this->client->disableReboot();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function load(): User
    {
        $this->loadFixtures('GoogleTasksConnectApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        return $user;
    }

    /**
     * @param array<int, array{id: string, title: string}> $taskLists
     *
     * Set once per test: the container refuses to replace a service it has
     * already built
     */
    private function stubGoogle(array $taskLists): void
    {
        $entries = [];
        foreach ($taskLists as $taskList) {
            $entry = new TaskList();
            $entry->setId($taskList['id']);
            $entry->setTitle($taskList['title']);
            $entries[] = $entry;
        }

        $apiClient = $this->createMock(GoogleTasksApiClient::class);
        $apiClient->method('listTaskLists')->willReturn($entries);

        self::getContainer()->set(GoogleTasksApiClient::class, $apiClient);
    }

    private function get(string $path, bool $authenticated = true): void
    {
        $this->client->request('GET', $path, [], [], $authenticated ? $this->authHeaders() : []);
    }

    /** @param array<string, mixed>|null $payload */
    private function post(string $path, ?array $payload = null, bool $authenticated = true): void
    {
        $this->client->request(
            'POST',
            $path,
            [],
            [],
            array_merge(
                ['CONTENT_TYPE' => 'application/json'],
                $authenticated ? $this->authHeaders() : [],
            ),
            null === $payload ? '' : json_encode($payload, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function body(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function reload(User $user): User
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        /** @var User $fresh */
        $fresh = $em->getRepository(User::class)->find($user->getId());

        return $fresh;
    }

    private function task(string $ref): Task
    {
        /** @var Task $fixture */
        $fixture = $this->getFixture($ref);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        /** @var Task $fresh */
        $fresh = $em->getRepository(Task::class)->find($fixture->getId());

        return $fresh;
    }

    public function testListingTheTaskListsRequiresAuthentication(): void
    {
        $this->load();

        $this->get('/api/calendar/google/task-lists', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testConnectingRequiresAuthentication(): void
    {
        $this->load();

        $this->post('/api/calendar/google/connect-tasks', ['googleTaskListId' => 'list-chores'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testDisconnectingRequiresAuthentication(): void
    {
        $this->load();

        $this->post('/api/calendar/google/disconnect-tasks', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** An account holding one list has no choice to offer, and says so in one line. */
    public function testListingAnswersTheOnlyListOfTheAccount(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->get('/api/calendar/google/task-lists');

        self::assertResponseIsSuccessful();
        self::assertSame([['id' => 'list-chores', 'title' => 'Mes tâches']], $this->body());
    }

    public function testListingAnswersEveryListOfTheAccount(): void
    {
        $this->load();
        $this->stubGoogle([
            ['id' => 'list-chores', 'title' => 'Mes tâches'],
            ['id' => 'list-courses', 'title' => 'Courses'],
        ]);

        $this->get('/api/calendar/google/task-lists');

        self::assertResponseIsSuccessful();
        self::assertSame([
            ['id' => 'list-chores', 'title' => 'Mes tâches'],
            ['id' => 'list-courses', 'title' => 'Courses'],
        ], $this->body());
    }

    public function testListingIsRefusedWithoutAGoogleAuthorization(): void
    {
        $this->load();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getRepository(User::class)->find($this->testUser->getId())->setGoogleRefreshToken(null);
        $em->flush();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->get('/api/calendar/google/task-lists');

        self::assertResponseStatusCodeSame(403);
    }

    public function testConnectingRejectsAMissingListId(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->post('/api/calendar/google/connect-tasks', []);

        self::assertResponseStatusCodeSame(400);
    }

    public function testConnectingRejectsAListTheAccountDoesNotHold(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->post('/api/calendar/google/connect-tasks', ['googleTaskListId' => 'list-someone-else']);

        // Stored on trust, every sync would then fail on a 404 nobody sees.
        self::assertResponseStatusCodeSame(404);
        self::assertSame('list-chores', $this->reload($user)->getGoogleTaskListId());
    }

    public function testConnectingStoresTheChosenListAndSyncsIt(): void
    {
        $user = $this->load();
        $this->stubGoogle([
            ['id' => 'list-chores', 'title' => 'Mes tâches'],
            ['id' => 'list-courses', 'title' => 'Courses'],
        ]);

        $this->post('/api/calendar/google/connect-tasks', ['googleTaskListId' => 'list-courses']);

        self::assertResponseIsSuccessful();
        self::assertSame(['googleTaskListId' => 'list-courses', 'title' => 'Courses'], $this->body());
        self::assertSame('list-courses', $this->reload($user)->getGoogleTaskListId());

        // One update, and the full scoped topic rather than a substring of it:
        // an update published outside the user's scope would contain this one
        // and pass (8380178), and publishing twice — once scoped, once not —
        // is the other half of that regression (c2d3758).
        $this->assertMercureUpdateCount(1);
        self::assertSame(
            ['/users/'.$user->getId().'/api/users/'.$user->getId()],
            $this->getMercureHub()->getUpdates()[0]->getTopics(),
        );
        self::assertSame(
            ['googleTaskListId' => 'list-courses'],
            array_diff_key(
                json_decode($this->getMercureHub()->getUpdates()[0]->getData(), true, 512, JSON_THROW_ON_ERROR),
                ['@id' => true],
            ),
        );

        // Nothing to reindex: `User::toSearchDocument()` does not carry the
        // chosen list, so the standard's index assertion is N/A here.
        $this->assertNoElasticsearchIndexDispatched(User::class);

        $pulls = array_filter(
            array_map(
                fn (Envelope $envelope) => $envelope->getMessage(),
                $this->getAsyncTransport()->getSent(),
            ),
            fn (object $message) => $message instanceof PullTasksFromGoogleCommand,
        );
        self::assertCount(1, $pulls, 'the list the owner just chose is pulled now, not at the next cron turn');
    }

    /**
     * Switching lists lets go of the tasks the previous one owned.
     *
     * Their Google identifiers belong to the old list: kept, a push would
     * address the new list by an id it never issued, and a pull of the new list
     * would never meet them to clean them up.
     */
    public function testSwitchingListsDetachesTheTasksOfThePreviousOne(): void
    {
        $this->load();
        $this->stubGoogle([
            ['id' => 'list-chores', 'title' => 'Mes tâches'],
            ['id' => 'list-courses', 'title' => 'Courses'],
        ]);

        $this->post('/api/calendar/google/connect-tasks', ['googleTaskListId' => 'list-courses']);

        self::assertResponseIsSuccessful();

        $detached = $this->task('task_from_chores');
        self::assertSame('Sortir les poubelles', $detached->getTitle(), 'the task itself stays, as a local one');
        self::assertNull($detached->getGoogleTaskId());
        self::assertNull($detached->getGoogleTaskListId());
        self::assertNull($detached->getGoogleTaskEtag());
        self::assertNull($detached->getGoogleTaskUpdatedAt());
    }

    public function testReconnectingTheSameListKeepsWhatGoogleGaveItsTasks(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->post('/api/calendar/google/connect-tasks', ['googleTaskListId' => 'list-chores']);

        self::assertResponseIsSuccessful();
        self::assertSame('g-task-chores-1', $this->task('task_from_chores')->getGoogleTaskId());
    }

    public function testDisconnectingClearsTheChoiceButKeepsTheTasks(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->post('/api/calendar/google/disconnect-tasks');

        self::assertResponseIsSuccessful();
        self::assertSame(['googleTaskListId' => null], $this->body());
        self::assertNull($this->reload($user)->getGoogleTaskListId());
        $this->assertMercureUpdateCount(1);
        self::assertSame(
            ['/users/'.$user->getId().'/api/users/'.$user->getId()],
            $this->getMercureHub()->getUpdates()[0]->getTopics(),
        );
        self::assertSame(
            ['googleTaskListId' => null],
            array_diff_key(
                json_decode($this->getMercureHub()->getUpdates()[0]->getData(), true, 512, JSON_THROW_ON_ERROR),
                ['@id' => true],
            ),
            'the screens listening have to be told the choice is gone, not just that something moved',
        );
        $this->assertNoElasticsearchIndexDispatched(User::class);

        // Kept on purpose: reconnecting the same list resumes on these rows
        // instead of pulling a second copy of every task.
        self::assertSame('g-task-chores-1', $this->task('task_from_chores')->getGoogleTaskId());
    }

    public function testDisconnectingTwiceIsASuccess(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'list-chores', 'title' => 'Mes tâches']]);

        $this->post('/api/calendar/google/disconnect-tasks');
        $this->post('/api/calendar/google/disconnect-tasks');

        self::assertResponseIsSuccessful();
        self::assertSame(['googleTaskListId' => null], $this->body());
    }
}
