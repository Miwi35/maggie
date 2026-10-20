<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Enum\ReviewStep;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * Where the user stands in the review of a month (MAG-456): read without
 * writing, moved on by step and by transaction handled, so the activity
 * resumes there on any device.
 */
class ReviewProgressControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/finance/review/progress';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock('2026-10-19 18:30:00 UTC'));
        $this->loadFixtures('review_progress.yaml');
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
    private function request(string $method, string $endpoint, mixed $body = null, bool $authenticated = true): array
    {
        $this->client->request(
            $method,
            $endpoint,
            [],
            [],
            ($authenticated ? $this->authHeaders() : []) + ['CONTENT_TYPE' => 'application/json'],
            null === $body ? null : (\is_string($body) ? $body : json_encode($body, \JSON_THROW_ON_ERROR)),
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
    }

    private function stored(string $month = '2026-10-01'): ?ReviewProgress
    {
        $this->em()->clear();
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());

        return $this->em()->getRepository(ReviewProgress::class)->findOneBy(['user' => $user, 'month' => new \DateTimeImmutable($month)]);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->request('GET', self::ENDPOINT.'?month=2026-10', authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'step' => 'balance'], authenticated: false);
        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->stored());
    }

    public function testABadMonthReturns400(): void
    {
        foreach (['2026-13', 'octobre', '2026-1', '2026-10-01'] as $month) {
            $this->request('GET', self::ENDPOINT.'?month='.$month);
            self::assertResponseStatusCodeSame(400, $month);

            $this->request('PUT', self::ENDPOINT, ['month' => $month, 'step' => 'balance']);
            self::assertResponseStatusCodeSame(400, $month);
        }

        $this->request('PUT', self::ENDPOINT, ['step' => 'balance']);
        self::assertResponseStatusCodeSame(400, 'month is required');
        $this->request('PUT', self::ENDPOINT, 'not json');
        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->stored());
    }

    public function testAnUnknownStepReturns400(): void
    {
        foreach (['bilan', '', 42] as $step) {
            $response = $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'step' => $step]);
            self::assertResponseStatusCodeSame(400, (string) $step);
            self::assertArrayHasKey('error', $response);
        }
        self::assertNull($this->stored());
    }

    public function testATransactionIdThatIsNotTheUsersReturns400(): void
    {
        $bad = [
            'not a ulid' => ['nope'],
            'another user\'s' => [$this->id('first'), $this->id('strangers')],
            'unknown' => ['01ARZ3NDEKTSV4RRFFQ69G5FAV'],
            'not a list' => 'nope',
            'too many' => array_fill(0, 201, $this->id('first')),
        ];
        foreach ($bad as $case => $ids) {
            $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'step' => 'balance', 'handledTransactionIds' => $ids]);
            self::assertResponseStatusCodeSame(400, $case);
        }

        self::assertNull($this->stored(), 'a refused call writes nothing, not even its step');
        $this->assertMercureUpdateCount(0);
    }

    public function testAMonthNeverWrittenStartsAtTheReviewAndStoresNothing(): void
    {
        $progress = $this->request('GET', self::ENDPOINT.'?month=2026-10');

        self::assertResponseIsSuccessful();
        self::assertSame(['month' => '2026-10', 'step' => 'review', 'handledTransactionIds' => [], 'updatedAt' => null, 'level' => 'month', 'period' => null, 'nextPeriod' => null], $progress, 'another user\'s progress is not mine');
        self::assertNull($this->stored());
    }

    public function testWithoutMonthTheCurrentMonthIsRead(): void
    {
        $progress = $this->request('GET', self::ENDPOINT);

        self::assertResponseIsSuccessful();
        self::assertSame((new \DateTimeImmutable('today'))->format('Y-m'), $progress['month']);
    }

    public function testAStepIsStoredAndPublished(): void
    {
        $progress = $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'step' => 'envelopes']);

        self::assertResponseIsSuccessful();
        self::assertSame(['month' => '2026-10', 'step' => 'envelopes', 'handledTransactionIds' => [], 'updatedAt' => '2026-10-19T18:30:00+00:00', 'level' => 'month', 'period' => null, 'nextPeriod' => null], $progress);
        $stored = $this->stored();
        self::assertNotNull($stored);
        self::assertSame(ReviewStep::Envelopes, $stored->getStep());

        $topic = '/api/review_progress/'.$stored->getId();
        $this->assertMercurePublishedOnce($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame('envelopes', end($payloads)['step']);
        $this->assertNoElasticsearchIndexDispatched(ReviewProgress::class);

        self::assertSame($progress, $this->request('GET', self::ENDPOINT.'?month=2026-10'), 'read back on any device');
    }

    public function testHandledTransactionsAreAddedOnceInTheOrderTheyCame(): void
    {
        $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'handledTransactionIds' => [$this->id('first')]]);
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        // Lowercase is the same ULID: stored as the queue sends it.
        $progress = $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'handledTransactionIds' => [$this->id('second'), strtolower($this->id('first'))]]);

        self::assertResponseIsSuccessful();
        self::assertSame([$this->id('first'), $this->id('second')], $progress['handledTransactionIds']);
        self::assertSame('review', $progress['step'], 'no step sent, the step stays');
        $stored = $this->stored();
        self::assertSame([$this->id('first'), $this->id('second')], $stored?->getHandledTransactionIds());
        self::assertCount(2, $this->em()->getRepository(ReviewProgress::class)->findAll(), 'one row for the month, and the other user\'s');

        $topic = '/api/review_progress/'.$stored->getId();
        $this->assertMercureUpdatePublished($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame([$this->id('first'), $this->id('second')], end($payloads)['handledTransactionIds']);

        // Sent again: nothing changes.
        $again = $this->request('PUT', self::ENDPOINT, ['month' => '2026-10', 'handledTransactionIds' => [$this->id('second')]]);
        self::assertSame([$this->id('first'), $this->id('second')], $again['handledTransactionIds']);

        $others = $this->em()->getRepository(ReviewProgress::class)->find($this->getFixture('others_progress')->getId());
        self::assertSame(ReviewStep::Projects, $others?->getStep());
        self::assertSame([], $others->getHandledTransactionIds());
    }

    public function testEachMonthHasItsOwnProgress(): void
    {
        $this->request('PUT', self::ENDPOINT, ['month' => '2026-09', 'step' => 'done']);
        self::assertResponseIsSuccessful();

        self::assertSame('review', $this->request('GET', self::ENDPOINT.'?month=2026-10')['step']);
        self::assertSame('done', $this->request('GET', self::ENDPOINT.'?month=2026-09')['step']);
        self::assertSame(ReviewStep::Done, $this->stored('2026-09-01')?->getStep());
    }

    /** @return iterable<string, array{string, string, string|null, string|null}> */
    public static function levels(): iterable
    {
        yield 'a plain month' => ['2026-10', 'month', null, null];
        yield 'a year no session is about is a plain month' => ['1999-03', 'month', null, null];
        yield 'March closes a quarter' => ['2027-03', 'quarter', '2027-Q1', '2027-Q2'];
        yield 'June closes a semester' => ['2027-06', 'semester', '2027-S1', '2027-S2'];
        yield 'September closes a quarter' => ['2026-09', 'quarter', '2026-Q3', '2026-Q4'];
        yield 'December closes the year and plans the next' => ['2026-12', 'year', '2026', '2027'];
    }

    #[DataProvider('levels')]
    public function testTheProgressSaysWhichReviewTheMonthIs(string $month, string $level, ?string $period, ?string $next): void
    {
        $progress = $this->request('GET', self::ENDPOINT.'?month='.$month);

        self::assertResponseIsSuccessful();
        self::assertSame($level, $progress['level']);
        self::assertSame($period, $progress['period']['key'] ?? null);
        self::assertSame($next, $progress['nextPeriod']['key'] ?? null);

        $written = $this->request('PUT', self::ENDPOINT, ['month' => $month, 'step' => 'projects']);
        self::assertSame([$level, $period, $next], [$written['level'], $written['period']['key'] ?? null, $written['nextPeriod']['key'] ?? null]);
    }

    public function testDecemberOffersTheYearAndTheNextYearsPlanning(): void
    {
        $progress = $this->request('GET', self::ENDPOINT.'?month=2026-12');

        self::assertSame(
            ['key' => '2026', 'level' => 'year', 'from' => '2026-01', 'to' => '2026-12', 'months' => 12],
            $progress['period'],
        );
        self::assertSame(
            ['key' => '2027', 'level' => 'year', 'from' => '2027-01', 'to' => '2027-12', 'months' => 12],
            $progress['nextPeriod'],
        );
    }

    public function testThePeriodStepsAreStoredAndResumed(): void
    {
        foreach (['period_balance', 'period_plan'] as $step) {
            $progress = $this->request('PUT', self::ENDPOINT, ['month' => '2027-03', 'step' => $step]);

            self::assertResponseIsSuccessful($step);
            self::assertSame($step, $progress['step']);
            self::assertSame($step, $this->request('GET', self::ENDPOINT.'?month=2027-03')['step']);
        }
        self::assertSame(ReviewStep::PeriodPlan, $this->stored('2027-03-01')?->getStep());
    }
}
