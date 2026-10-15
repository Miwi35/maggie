<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The four views of the tasks: Aujourd'hui, À venir, À faire un jour, Faites (MAG-408). */
class TaskViewsApiTest extends WebTestCase
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
        Clock::set(new MockClock('2026-10-13 12:00:00 UTC'));
        $this->loadFixtures('TaskViewsApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<string> */
    private function titles(string $view, string $query = ''): array
    {
        $body = $this->get('/api/tasks/view/'.$view.$query);
        self::assertResponseIsSuccessful();

        return array_column($body['member'], 'title');
    }

    public function testEveryViewRequiresAuthentication(): void
    {
        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            $this->client->request('GET', '/api/tasks/view/'.$view);

            self::assertResponseStatusCodeSame(401, $view);
        }
    }

    public function testAnUnknownViewIsRefused(): void
    {
        $body = $this->get('/api/tasks/view/yesterday');

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('upcoming', $body['error']);
    }

    /** @return iterable<string, array{string}> */
    public static function invalidPages(): iterable
    {
        yield 'page zero' => ['?page=0'];
        yield 'negative page' => ['?page=-1'];
        yield 'page not a number' => ['?page=abc'];
        yield 'page a decimal' => ['?page=1.5'];
        yield 'page an array' => ['?page[]=1'];
        yield 'itemsPerPage zero' => ['?itemsPerPage=0'];
        yield 'itemsPerPage over the limit' => ['?itemsPerPage=101'];
        yield 'itemsPerPage not a number' => ['?itemsPerPage=many'];
    }

    #[DataProvider('invalidPages')]
    public function testAnInvalidPageIsRefused(string $query): void
    {
        $this->get('/api/tasks/view/done'.$query);

        self::assertResponseStatusCodeSame(400);
    }

    public function testTodayHoldsTheLateAndTodaysTasksEachFollowedByItsSubtasks(): void
    {
        self::assertSame(
            ['Late', 'Due today', 'Undated subtask of a task due today'],
            $this->titles('today'),
        );
    }

    public function testUpcomingIsByDueDayThenByScore(): void
    {
        self::assertSame([
            'Due tomorrow',
            'Due the 18th, critical',
            'Due the 18th, low',
            'Dated subtask of a far task',
            'Due the 30th',
        ], $this->titles('upcoming'));
    }

    public function testUpcomingCarriesTheOriginalDueDayAndTheComputedFields(): void
    {
        $first = $this->get('/api/tasks/view/upcoming')['member'][0];

        self::assertSame('2026-10-14', $first['dueDate']);
        self::assertSame('/api/tasks/'.$this->getFixture('up_tomorrow')->getId(), $first['@id']);
        self::assertContains($first['urgency'], ['low', 'normal', 'high', 'critical']);
        self::assertIsInt($first['orderScore']);
    }

    public function testSomedayIsByPriorityThenByPositionTheUnpositionedLast(): void
    {
        self::assertSame([
            'Someday, high priority',
            'Someday, medium first',
            'Someday, medium second',
            'Someday, medium unpositioned',
            'Someday, low priority',
        ], $this->titles('someday'));
    }

    public function testDoneIsTheMostRecentlyDoneFirst(): void
    {
        self::assertSame(
            ['Done yesterday', 'Done subtask', 'Done last week', 'Done a while ago'],
            $this->titles('done'),
        );
    }

    public function testDoneIsPaged(): void
    {
        $first = $this->get('/api/tasks/view/done?itemsPerPage=3');
        self::assertSame(['Done yesterday', 'Done subtask', 'Done last week'], array_column($first['member'], 'title'));
        self::assertSame(4, $first['totalItems']);
        self::assertSame(1, $first['page']);
        self::assertSame(3, $first['itemsPerPage']);

        self::assertSame(['Done a while ago'], $this->titles('done', '?itemsPerPage=3&page=2'));
        self::assertSame([], $this->titles('done', '?itemsPerPage=3&page=3'));
    }

    public function testEachTaskIsInExactlyOneViewAndTheCountsSayHowMany(): void
    {
        $seen = [];
        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            $body = $this->get('/api/tasks/view/'.$view);
            self::assertSame(['today' => 3, 'upcoming' => 5, 'someday' => 5, 'done' => 4], $body['counts']);
            self::assertSame($body['counts'][$view], $body['totalItems']);
            foreach ($body['member'] as $member) {
                self::assertArrayNotHasKey($member['id'], $seen, $member['title'].' is in two views');
                $seen[$member['id']] = $view;
            }
        }

        self::assertCount(17, $seen, 'the 17 tasks of the user, none left out');
    }

    public function testNoViewShowsAnotherUsersTasks(): void
    {
        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            foreach ($this->titles($view) as $title) {
                self::assertStringNotContainsString('Someone else', $title);
            }
        }
    }

    public function testTheCountsAndTheViewsAreTheUsersOwn(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $body = $this->get('/api/tasks/view/someday');

        self::assertResponseIsSuccessful();
        self::assertSame(['today' => 1, 'upcoming' => 0, 'someday' => 1, 'done' => 1], $body['counts']);
        self::assertSame(["Someone else's someday task"], array_column($body['member'], 'title'));
    }

    public function testTickingATaskSendsItToDoneAndUnTickingPutsItBack(): void
    {
        $task = $this->getFixture('up_tomorrow');

        $this->patch($task, ['completedAt' => '2026-10-13T12:00:00+00:00']);

        self::assertResponseIsSuccessful();
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
        self::assertNotContains('Due tomorrow', $this->titles('upcoming'));
        self::assertSame('Due tomorrow', $this->titles('done')[0]);

        $this->patch($task, ['completedAt' => null]);

        self::assertResponseIsSuccessful();
        self::assertSame('Due tomorrow', $this->titles('upcoming')[0]);
        self::assertNotContains('Due tomorrow', $this->titles('done'));
    }

    public function testTickingAnUndatedTaskLeavesSomedayForDone(): void
    {
        $this->patch($this->getFixture('some_high'), ['completedAt' => '2026-10-13T12:00:00+00:00']);

        self::assertResponseIsSuccessful();
        self::assertNotContains('Someday, high priority', $this->titles('someday'));
        self::assertSame('Someday, high priority', $this->titles('done')[0]);
    }

    public function testTheViewsAreAReadAndPublishNothing(): void
    {
        foreach (['today', 'upcoming', 'someday', 'done'] as $view) {
            $this->titles($view);
        }

        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Task::class);
    }

    /** @param array<string, mixed> $body */
    private function patch(Task $task, array $body): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }
}
