<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\TaskTransitionRecorder;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Event\TaskReopened;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The day over REST (MAG-497): five slots at most, « Maintenant », the reserve, « reportée ». */
class DayPlanApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-14';
    private const string ENDPOINT = '/api/day-plans/'.self::TODAY;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock(self::TODAY.' 12:00:00 UTC'));
        $this->loadFixtures('DayPlanApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function get(string $endpoint = self::ENDPOINT): array
    {
        $this->client->request('GET', $endpoint, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function put(mixed $body, string $endpoint = self::ENDPOINT): array
    {
        $this->client->request('PUT', $endpoint, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], \is_string($body) ? $body : json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param list<string> $refs */
    private function putTasks(array $refs, string $endpoint = self::ENDPOINT): array
    {
        return $this->put(['slots' => array_map(fn (string $ref): array => ['type' => 'task', 'taskId' => $this->id($ref)], $refs)], $endpoint);
    }

    private function stored(string $date = self::TODAY): ?DayPlan
    {
        $this->em()->clear();
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());

        return $this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $user, 'date' => new \DateTimeImmutable($date)]);
    }

    /** @return list<string> the stored slots, by fixture reference */
    private function storedRefs(): array
    {
        $names = ['t1', 't2', 't3', 't4', 't5', 't6', 'critical'];
        $refs = array_combine(array_map($this->id(...), $names), $names);

        return array_map(static fn (string $id): string => $refs[$id] ?? $id, $this->stored()?->getTaskIds() ?? []);
    }

    private static function titles(array $tasks): array
    {
        return array_map(static fn (array $task): string => $task['title'], $tasks);
    }

    private function patchTask(string $ref, array $payload): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$this->id($ref), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return list<TaskCompleted|TaskReopened> what the event bus delivered since the last call */
    private function events(): array
    {
        $recorder = self::getContainer()->get(TaskTransitionRecorder::class);
        $events = $recorder->events();
        $recorder->clear();

        return $events;
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PUT', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"slots": []}');
        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->stored());
    }

    public function testTheDayIsCreatedEmptyOnFirstReadAndEverythingOpenWaitsInTheReserve(): void
    {
        self::assertNull($this->stored());

        $day = $this->get();

        self::assertResponseIsSuccessful();
        $stored = $this->stored();
        self::assertNotNull($stored);
        self::assertSame((string) $stored->getId(), $day['id']);
        self::assertSame(self::TODAY, $day['date']);
        self::assertSame([], $day['slots']);
        self::assertNull($day['now']);
        self::assertNull($day['confirmedAt'], 'a day nobody touched is not settled');
        self::assertSame(0, $day['done']);
        self::assertSame(0, $day['total']);
        self::assertEqualsCanonicalizing(['Arroser les plantes', 'Déclarer les impôts', 'Payer la cantine', 'Rappeler le garage', 'Ranger le garage', 'Réserver le train', 'Trier le courrier'], self::titles($day['reserve']), 'open, top-level, mine: no subtask, no done task, nobody else\'s');
        self::assertSame('Déclarer les impôts', $day['reserve'][0]['title'], 'the highest score first');
        $this->assertMercurePublishedOnce('/api/day_plans/'.$stored->getId());
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);

        $this->get();
        self::assertCount(1, $this->em()->getRepository(DayPlan::class)->findAll(), 'read again, the same day');
    }

    /** @return iterable<string, array{string}> */
    public static function notADay(): iterable
    {
        yield 'words' => ['demain'];
        yield 'the 45th' => ['2026-10-45'];
    }

    #[DataProvider('notADay')]
    public function testADateThatIsNotADayIs400(string $date): void
    {
        $this->get('/api/day-plans/'.$date);
        self::assertResponseStatusCodeSame(400);

        $this->putTasks(['t1'], '/api/day-plans/'.$date);
        self::assertResponseStatusCodeSame(400);
    }

    public function testPutSetsTheFiveInOrderSettlesTheDayAndPublishes(): void
    {
        $day = $this->putTasks(['t1', 't2', 't3', 't4', 't5']);

        self::assertResponseIsSuccessful();
        self::assertSame(['t1', 't2', 't3', 't4', 't5'], $this->storedRefs());
        $stored = $this->stored();
        self::assertNotNull($stored?->getConfirmedAt(), 'the first change by hand settles the day');
        self::assertSame([1, 2, 3, 4, 5], array_column($stored->getSlots(), 'position'));
        self::assertSame([1, 2, 3, 4, 5], array_column($day['slots'], 'position'));
        self::assertSame('task', $day['slots'][0]['type']);
        self::assertSame([
            'id' => $this->id('t1'),
            'title' => 'Rappeler le garage',
            'estimatedMinutes' => 10,
            'firstStep' => 'Trouver le numéro',
            'completedAt' => null,
            'dueDate' => '2026-10-12',
            'postponedSince' => '2026-10-12',
            'urgency' => 'high',
            'criticality' => 'low',
        ], $day['slots'][0]['task']);
        self::assertSame(1, $day['now']['position']);
        self::assertSame('Rappeler le garage', $day['now']['task']['title']);
        self::assertSame(0, $day['done']);
        self::assertSame(5, $day['total']);
        self::assertSame(['Déclarer les impôts', 'Ranger le garage'], self::titles($day['reserve']));
        $this->assertMercureUpdatePublished('/api/day_plans/'.$stored->getId());
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);
    }

    /** The first acceptance criterion: a 6th goes to the reserve; put in place of the 3rd, the 3rd goes back there. */
    public function testASixthWaitsInTheReserveAndTakingTheThirdsPlaceSendsTheThirdBack(): void
    {
        $this->putTasks(['t1', 't2', 't3', 't4', 't5']);

        $this->putTasks(['t1', 't2', 't3', 't4', 't5', 't6']);
        self::assertResponseStatusCodeSame(400);
        self::assertSame(['t1', 't2', 't3', 't4', 't5'], $this->storedRefs(), 'a refused list changes nothing');
        self::assertContains('Ranger le garage', self::titles($this->get()['reserve']));

        $day = $this->putTasks(['t1', 't2', 't6', 't4', 't5']);

        self::assertResponseIsSuccessful();
        self::assertSame(['t1', 't2', 't6', 't4', 't5'], $this->storedRefs());
        self::assertSame('Ranger le garage', $day['slots'][2]['task']['title']);
        self::assertContains('Arroser les plantes', self::titles($day['reserve']));
        self::assertNotContains('Ranger le garage', self::titles($day['reserve']));
    }

    public function testPickingFromTheReserveFillsAFreeSlot(): void
    {
        $this->putTasks(['t1', 't2']);

        $day = $this->putTasks(['t1', 't2', 't6']);

        self::assertSame(['t1', 't2', 't6'], $this->storedRefs());
        self::assertSame(3, $day['total']);
        self::assertNotContains('Ranger le garage', self::titles($day['reserve']));
    }

    /** @return iterable<string, array{\Closure(self): array<string, mixed>|string, string}> */
    public static function refusedLists(): iterable
    {
        yield 'another user\'s task' => [fn (self $t): array => ['slots' => [['type' => 'task', 'taskId' => $t->id('others')]]], 'Task not found'];
        yield 'an unknown task' => [fn (self $t): array => ['slots' => [['type' => 'task', 'taskId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV']]], 'Task not found'];
        yield 'a saga step' => [fn (self $t): array => ['slots' => [['type' => 'sagaStep', 'taskId' => $t->id('t1')]]], 'saga step'];
        yield 'an unknown type' => [fn (self $t): array => ['slots' => [['type' => 'meal', 'taskId' => $t->id('t1')]]], 'unknown type'];
        yield 'twice the same task' => [fn (self $t): array => ['slots' => [['type' => 'task', 'taskId' => $t->id('t1')], ['type' => 'task', 'taskId' => $t->id('t1')]]], 'one slot'];
        yield 'no task id' => [fn (self $t): array => ['slots' => [['type' => 'task']]], 'taskId'];
        yield 'no slots' => [fn (self $t): array => ['tasks' => []], 'slots'];
        yield 'slots not a list' => [fn (self $t): array => ['slots' => ['a' => 1]], 'slots'];
        yield 'confirm not a boolean' => [fn (self $t): array => ['slots' => [], 'confirm' => 'yes'], 'confirm'];
        yield 'not JSON' => [fn (self $t): string => '{', 'slots'];
    }

    /** @param \Closure(self): (array<string, mixed>|string) $body */
    #[DataProvider('refusedLists')]
    public function testARefusedListIs400AndChangesNothing(\Closure $body, string $message): void
    {
        $this->putTasks(['t2']);

        $data = $this->put($body($this));

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsStringIgnoringCase($message, $data['error']);
        self::assertSame(['t2'], $this->storedRefs());
    }

    /** The second acceptance criterion: ticking the first moves « Maintenant » to the next one not done, and raises TaskCompleted. */
    public function testTickingTheFirstMovesNowToTheNextAndRaisesTaskCompleted(): void
    {
        $this->putTasks(['t1', 't2', 't3', 't4', 't5']);
        $planId = (string) $this->stored()?->getId();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->events();

        $this->patchTask('t1', ['completedAt' => '2026-10-14T10:00:00+02:00']);

        self::assertResponseIsSuccessful();
        $completed = array_values(array_filter($this->events(), static fn (object $e): bool => $e instanceof TaskCompleted));
        self::assertCount(1, $completed);
        self::assertSame($this->id('t1'), $completed[0]->taskId);
        self::assertSame($this->id('test_user'), $completed[0]->userId);
        $this->assertMercureUpdatePublished('/api/day_plans/'.$planId);
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $this->id('t1'));

        $day = $this->get();
        self::assertSame(2, $day['now']['position']);
        self::assertSame('Trier le courrier', $day['now']['task']['title']);
        self::assertSame(1, $day['done']);
        self::assertSame(5, $day['total']);
        self::assertNotNull($day['slots'][0]['task']['completedAt']);
        self::assertNull($day['slots'][0]['task']['postponedSince'], 'done: put off no more');

        $this->patchTask('t1', ['completedAt' => null]);
        self::assertResponseIsSuccessful();
        self::assertContainsOnlyInstancesOf(TaskReopened::class, array_values(array_filter($this->events(), static fn (object $e): bool => $e instanceof TaskReopened || $e instanceof TaskCompleted)));
        self::assertSame('Rappeler le garage', $this->get()['now']['task']['title']);
    }

    public function testEditingATaskWithoutTickingItRaisesNoEvent(): void
    {
        $this->patchTask('t2', ['title' => 'Trier tout le courrier']);

        self::assertResponseIsSuccessful();
        self::assertSame([], array_filter($this->events(), static fn (object $e): bool => $e instanceof TaskCompleted || $e instanceof TaskReopened));
    }

    /** The third acceptance criterion: due Monday, not done, read on Wednesday. */
    public function testATaskDueMondayIsPutOffSinceMondayAndACriticalOneKeepsItsMark(): void
    {
        $reserve = $this->get()['reserve'];
        $byTitle = array_column($reserve, null, 'title');

        self::assertSame('2026-10-12', $byTitle['Rappeler le garage']['postponedSince']);
        self::assertSame('2026-10-12', $byTitle['Déclarer les impôts']['postponedSince']);
        self::assertSame('critical', $byTitle['Déclarer les impôts']['urgency'], 'critical keeps its word and its icon');
        self::assertNull($byTitle['Trier le courrier']['postponedSince']);

        $this->client->request('GET', '/api/tasks/'.$this->id('t1'), [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        $task = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('2026-10-12', $task['postponedSince']);
    }

    /** What yesterday left undone is not carried over in silence: back in the reserve, « reportée ». */
    public function testWhatYesterdayLeftUndoneWaitsInTheReservePutOffSinceYesterday(): void
    {
        $this->putTasks(['t6', 't2'], '/api/day-plans/2026-10-13');
        $this->patchTask('t2', ['completedAt' => '2026-10-13T18:00:00+02:00']);

        $day = $this->get();

        self::assertSame([], $day['slots'], 'a new day starts empty');
        $byTitle = array_column($day['reserve'], null, 'title');
        self::assertSame('2026-10-13', $byTitle['Ranger le garage']['postponedSince']);
        self::assertArrayNotHasKey('Trier le courrier', $byTitle, 'done yesterday, gone');
    }

    public function testAFutureDayCanBeWrittenAndAProposalLeavesItUnsettled(): void
    {
        $day = $this->put(['slots' => [['type' => 'task', 'taskId' => $this->id('t3')]], 'confirm' => false], '/api/day-plans/2026-10-15');

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10-15', $day['date']);
        self::assertNull($day['confirmedAt']);
        $stored = $this->stored('2026-10-15');
        self::assertNotNull($stored);
        self::assertSame([$this->id('t3')], $stored->getTaskIds());
        self::assertNull($stored->getConfirmedAt());
        self::assertNull($this->stored(), 'today untouched');
    }

    public function testADeletedTaskLeavesItsSlot(): void
    {
        $this->putTasks(['t1', 't2', 't3']);

        $this->client->request('DELETE', '/api/tasks/'.$this->id('t2'), [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        $day = $this->get();
        self::assertSame(['Rappeler le garage', 'Arroser les plantes'], array_map(static fn (array $slot): string => $slot['task']['title'], $day['slots']));
        self::assertSame([1, 2], array_column($day['slots'], 'position'));
    }

    /** The task's sheet: how long it takes and its first step, Maggie's own fields. */
    public function testTheEstimateAndTheFirstStepAreWrittenOnTheTask(): void
    {
        $this->patchTask('t2', ['estimatedMinutes' => 25, 'firstStep' => 'Ouvrir la boîte']);

        self::assertResponseIsSuccessful();
        $this->em()->clear();
        $task = $this->em()->find(Task::class, $this->getFixture('t2')->getId());
        self::assertSame(25, $task?->getEstimatedMinutes());
        self::assertSame('Ouvrir la boîte', $task?->getFirstStep());
        $this->assertMercureUpdatePublished('/api/tasks/'.$this->id('t2'));
        $this->assertElasticsearchIndexDispatchedFor(Task::class, $this->id('t2'));

        $this->patchTask('t2', ['estimatedMinutes' => null, 'firstStep' => null]);
        $this->em()->clear();
        $task = $this->em()->find(Task::class, $this->getFixture('t2')->getId());
        self::assertNull($task?->getEstimatedMinutes());
        self::assertNull($task?->getFirstStep());
    }

    public function testAnEstimateOutOfRangeIsRefused(): void
    {
        $this->patchTask('t2', ['estimatedMinutes' => 0]);

        self::assertResponseStatusCodeSame(422);
        $this->em()->clear();
        self::assertNull($this->em()->find(Task::class, $this->getFixture('t2')->getId())?->getEstimatedMinutes());
    }
}
