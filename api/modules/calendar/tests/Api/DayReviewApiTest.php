<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\DayReview;
use Maggie\Calendar\Entity\Task;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The evening over REST (MAG-500): the review and what helped, and the three
 * ways out of a task left — tomorrow's five, the reserve, set aside — through
 * the day and the task they write.
 */
class DayReviewApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-14';
    private const string TOMORROW = '2026-10-15';
    private const string ENDPOINT = '/api/day-reviews/'.self::TODAY;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock(self::TODAY.' 19:05:00 UTC'));
        $this->loadFixtures('DayReviewApiTest.yaml');
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
    private function request(string $method, string $endpoint, mixed $body = null): array
    {
        $this->client->request($method, $endpoint, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], null === $body ? null : (\is_string($body) ? $body : json_encode($body, JSON_THROW_ON_ERROR)));

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param list<string> $refs */
    private function putDay(string $date, array $refs, bool $confirm = true): array
    {
        return $this->request('PUT', '/api/day-plans/'.$date, [
            'slots' => array_map(fn (string $ref): array => ['type' => 'task', 'taskId' => $this->id($ref)], $refs),
            'confirm' => $confirm,
        ]);
    }

    private function user(): User
    {
        $this->em()->clear();

        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function storedReview(): ?DayReview
    {
        return $this->em()->getRepository(DayReview::class)->findOneBy(['user' => $this->user(), 'date' => new \DateTimeImmutable(self::TODAY)]);
    }

    private function storedDay(string $date): ?DayPlan
    {
        return $this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $this->user(), 'date' => new \DateTimeImmutable($date)]);
    }

    private static function titles(array $tasks): array
    {
        return array_map(static fn (array $task): string => $task['title'], $tasks);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PUT', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"helped": []}');
        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->storedReview());
    }

    public function testTheReviewIsCreatedEmptyAndNotDoneOnFirstRead(): void
    {
        $review = $this->request('GET', self::ENDPOINT);

        self::assertResponseIsSuccessful();
        $stored = $this->storedReview();
        self::assertNotNull($stored);
        self::assertSame(['id' => (string) $stored->getId(), 'date' => self::TODAY, 'helped' => [], 'reviewedAt' => null], $review, 'another user\'s review is not mine');
        $this->assertMercurePublishedOnce('/api/day_reviews/'.$stored->getId());
        $this->assertNoElasticsearchIndexDispatched(DayReview::class);

        $this->request('GET', self::ENDPOINT);
        self::assertCount(2, $this->em()->getRepository(DayReview::class)->findAll(), 'read again, the same review');
    }

    public function testGoodNightRecordsWhatHelpedClosesTheReviewAndPublishes(): void
    {
        $review = $this->request('PUT', self::ENDPOINT, ['helped' => ['timer', 'start_small']]);

        self::assertResponseIsSuccessful();
        self::assertSame(['start_small', 'timer'], $review['helped'], 'in the order of the list');
        self::assertSame('2026-10-14T19:05:00+00:00', $review['reviewedAt']);
        $stored = $this->storedReview();
        self::assertSame(['start_small', 'timer'], $stored?->getHelped());
        self::assertNotNull($stored->getReviewedAt(), 'done: it does not open again');

        $topic = '/api/day_reviews/'.$stored->getId();
        $this->assertMercureUpdatePublished($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame(['start_small', 'timer'], end($payloads)['helped']);
        $this->assertNoElasticsearchIndexDispatched(DayReview::class);

        $others = $this->em()->getRepository(DayReview::class)->find($this->getFixture('others_review')->getId());
        self::assertSame(['timer'], $others?->getHelped());
        self::assertNull($others->getReviewedAt());
    }

    public function testGoodNightWithNothingTickedStillClosesTheReview(): void
    {
        $review = $this->request('PUT', self::ENDPOINT, ['helped' => []]);

        self::assertResponseIsSuccessful();
        self::assertSame([], $review['helped']);
        self::assertNotNull($this->storedReview()?->getReviewedAt());
    }

    /** @return iterable<string, array{string, string}> */
    public static function badBodies(): iterable
    {
        yield 'a value outside the list' => ['{"helped": ["start_small", "coffee"]}', 'coffee'];
        yield 'Maggie, not there yet' => ['{"helped": ["with_maggie"]}', 'with_maggie'];
        yield 'a value that is not a string' => ['{"helped": [3]}', 'helped'];
        yield 'helped that is not a list' => ['{"helped": {"timer": true}}', 'helped'];
        yield 'no helped' => ['{}', 'helped'];
        yield 'not JSON' => ['timer', 'helped'];
    }

    #[DataProvider('badBodies')]
    public function testABadBodyIsA400AndChangesNothing(string $body, string $named): void
    {
        $this->request('GET', self::ENDPOINT);
        $this->resetMercure();

        $data = $this->request('PUT', self::ENDPOINT, $body);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($named, $data['error']);
        $stored = $this->storedReview();
        self::assertSame([], $stored?->getHelped());
        self::assertNull($stored->getReviewedAt());
        $this->assertMercureUpdateCount(0);
    }

    public function testADateThatIsNotADayIs400(): void
    {
        $this->request('GET', '/api/day-reviews/ce-soir');
        self::assertResponseStatusCodeSame(400);

        $this->request('PUT', '/api/day-reviews/2026-10-45', ['helped' => []]);
        self::assertResponseStatusCodeSame(400);
    }

    /**
     * The acceptance criteria of the evening, as the screens write them: of
     * « 3 sur 5 », one left goes in tomorrow's five — written, not settled,
     * so the morning still proposes, and keeps it — and the other is set
     * aside: no due day, out of today, in « Un jour »; « Annuler » puts both back.
     */
    public function testTomorrowsFiveAndSetAsideWriteTheDayAndTheTask(): void
    {
        $this->putDay(self::TODAY, ['t1', 't2', 't3', 't4', 't5']);
        $today = $this->request('GET', '/api/day-plans/'.self::TODAY);
        self::assertSame(3, $today['done']);
        self::assertSame(5, $today['total']);
        $this->resetMercure();

        // « Dans mes 5 de demain »
        $tomorrow = $this->putDay(self::TOMORROW, ['t4'], confirm: false);
        self::assertResponseIsSuccessful();
        self::assertSame(['Réserver le train'], self::titles(array_column($tomorrow['slots'], 'task')));
        $stored = $this->storedDay(self::TOMORROW);
        self::assertSame([$this->id('t4')], $stored?->getTaskIds());
        self::assertNull($stored->getConfirmedAt(), 'the morning still opens tomorrow');
        $this->assertMercureUpdatePublished('/api/day_plans/'.$stored->getId());

        Clock::set(new MockClock(self::TOMORROW.' 06:30:00 UTC'));
        $proposal = $this->request('GET', '/api/day-plans/'.self::TOMORROW.'/proposal');
        self::assertResponseIsSuccessful();
        self::assertContains('Réserver le train', self::titles(array_column($proposal['slots'], 'task')), 'the morning keeps what the evening wrote');
        Clock::set(new MockClock(self::TODAY.' 19:05:00 UTC'));

        // « Mettre de côté »
        $this->client->request('PATCH', '/api/tasks/'.$this->id('t5'), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), '{"dueDate": null}');
        self::assertResponseIsSuccessful();
        $this->putDay(self::TODAY, ['t1', 't2', 't3', 't4']);
        self::assertResponseIsSuccessful();

        self::assertNull($this->em()->find(Task::class, $this->getFixture('t5')->getId())?->getDueDate());
        self::assertNotContains($this->id('t5'), $this->storedDay(self::TODAY)?->getTaskIds() ?? []);
        $someday = $this->request('GET', '/api/tasks/view/someday');
        self::assertContains('Payer la cantine', self::titles($someday['member']));
    }

    public function testAFifthAndASixthForTomorrowIsRefused(): void
    {
        $this->putDay(self::TOMORROW, ['t1', 't2', 't3', 't4', 't5'], confirm: false);
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $refused = $this->putDay(self::TOMORROW, ['t1', 't2', 't3', 't4', 't5', 't6'], confirm: false);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('5', $refused['error']);
        self::assertCount(5, $this->storedDay(self::TOMORROW)?->getTaskIds() ?? []);
        $this->assertMercureUpdateCount(0);
    }
}
