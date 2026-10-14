<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** « Aujourd'hui », and the lateness every read of a task carries (MAG-405). */
class TaskViewApiTest extends WebTestCase
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
        $this->pinClock('2026-10-13 12:00:00 UTC');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function pinClock(string $instant): void
    {
        Clock::set(new MockClock($instant));
    }

    private function load(): void
    {
        $this->loadFixtures('TaskViewApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function get(string $uri): array
    {
        $this->client->request('GET', $uri, [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<array<string, mixed>> */
    private function today(): array
    {
        $body = $this->get('/api/tasks/view/today');
        self::assertResponseIsSuccessful();

        return $body['member'];
    }

    public function testTheViewRequiresAuthentication(): void
    {
        $this->load();

        $this->client->request('GET', '/api/tasks/view/today');

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnUnknownViewIsRefused(): void
    {
        $this->load();

        $this->get('/api/tasks/view/yesterday');

        self::assertResponseStatusCodeSame(400);
    }

    public function testTodayHoldsTheLateAndTodaysTasksTheMostLateFirstEachFollowedByItsSubtasks(): void
    {
        $this->load();

        $members = $this->today();

        self::assertSame([
            'Late three days',
            'Sub of late, first',
            'Sub of late, second',
            'Late subtask of a future task',
            'Late one day',
            'Due today',
        ], array_column($members, 'title'));
        self::assertSame([3, null, null, 1, 1, null], array_column($members, 'overdueDays'));
    }

    public function testTodayLeavesOutUndatedFutureDoneAndOtherUsersTasks(): void
    {
        $this->load();

        $titles = array_column($this->today(), 'title');

        foreach (['Undated', 'Due tomorrow', 'Late but done', 'Sub of late, done', "Someone else's late task"] as $left) {
            self::assertNotContains($left, $titles);
        }
    }

    public function testTodayCarriesTheOriginalDueDayAndTheTaskIri(): void
    {
        $this->load();

        $late = $this->today()[0];

        self::assertSame('2026-10-10', $late['dueDate']);
        self::assertSame('/api/tasks/'.$this->getFixture('late_three_days')->getId(), $late['@id']);
        self::assertSame((string) $this->getFixture('late_three_days')->getId(), $late['id']);
    }

    public function testTodayIsAnEmptyCollectionWhenNothingIsDue(): void
    {
        $this->loadFixtures('TaskViewApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('other_user'));
        $this->pinClock('2026-09-01 12:00:00 UTC');

        self::assertSame([], $this->today());
    }

    public function testTheDayTurnsAtTheMidnightOfTheUsersTimezone(): void
    {
        $this->load();

        // 23:30 in Paris on the 13th (UTC+2): the task due the 13th is due today.
        $this->pinClock('2026-10-13 21:30:00 UTC');
        $before = array_column($this->today(), 'overdueDays', 'title');

        // 00:30 in Paris on the 14th: the 13th is yesterday, and the 14th is today.
        $this->pinClock('2026-10-13 22:30:00 UTC');
        $after = array_column($this->today(), 'overdueDays', 'title');

        self::assertNull($before['Due today']);
        self::assertSame(1, $after['Due today']);
        self::assertArrayNotHasKey('Due tomorrow', $before);
        self::assertArrayHasKey('Due tomorrow', $after, 'the 14th is today after Paris midnight');
        self::assertNull($after['Due tomorrow']);
        self::assertSame(4, $after['Late three days']);
    }

    public function testViewIsAReadAndPublishesNothing(): void
    {
        $this->load();

        $this->today();

        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Task::class);
    }

    public function testTickingATaskFromTheViewPublishesItsUpdateAndTheTaskLeavesTheView(): void
    {
        $this->load();
        $late = $this->getFixture('late_one_day');

        $this->client->request('PATCH', '/api/tasks/'.$late->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['completedAt' => '2026-10-13T12:00:00+00:00'], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
        self::assertNotContains('Late one day', array_column($this->today(), 'title'));
    }

    public function testTheItemAndTheCollectionCarryTheLateness(): void
    {
        $this->load();
        $late = $this->getFixture('late_three_days');

        $item = $this->get('/api/tasks/'.$late->getId());
        self::assertResponseIsSuccessful();
        self::assertSame(3, $item['overdueDays']);

        $collection = $this->get('/api/tasks?pagination=false');
        self::assertResponseIsSuccessful();
        // A late-free task has no `overdueDays` at all in a collection: API Platform leaves nulls out.
        $byTitle = [];
        foreach ($collection['member'] as $member) {
            $byTitle[$member['title']] = $member['overdueDays'] ?? null;
        }
        self::assertSame(3, $byTitle['Late three days']);
        self::assertSame(1, $byTitle['Late one day']);
        self::assertNull($byTitle['Due today']);
        self::assertNull($byTitle['Late but done'], 'a done task is not late');
        self::assertNull($byTitle['Undated']);
    }

    public function testLatenessCannotBeWritten(): void
    {
        $this->load();
        $today = $this->getFixture('due_today');

        $this->client->request('PATCH', '/api/tasks/'.$today->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['overdueDays' => 9], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertNull($this->get('/api/tasks/'.$today->getId())['overdueDays'] ?? null);
    }
}
