<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FinanceDashboardRefreshTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SqlRecorder;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\FinanceDashboardSnapshot;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Maggie\Finance\UseCase\GetFinanceScores;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\HttpFoundation\JsonResponse;

/**
 * The Finance Dashboard's read model (MAG-545): the endpoint reads what the
 * rebuild stored — a couple of statements, nothing written — and computes live,
 * still without writing, while a write is waiting to be folded in.
 */
class FinanceDashboardProjectionTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use FinanceDashboardRefreshTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function user(): User
    {
        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function snapshot(): ?FinanceDashboardSnapshot
    {
        $this->em()->clear();

        return $this->em()->getRepository(FinanceDashboardSnapshot::class)->findOneBy(['user' => $this->user()]);
    }

    /** @return array{queries: list<string>, body: string} the statements the request ran, and the answer */
    private function read(string $query = ''): array
    {
        SqlRecorder::reset();
        $this->client->request('GET', '/api/finance/dashboard'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $queries = SqlRecorder::statements();

        return ['queries' => $queries, 'body' => (string) $this->client->getResponse()->getContent()];
    }

    /** @param list<string> $queries */
    private static function assertNoWrite(array $queries): void
    {
        foreach ($queries as $sql) {
            self::assertDoesNotMatchRegularExpression('/^\s*(INSERT|UPDATE|DELETE)\b/i', $sql, 'a GET wrote: '.$sql);
        }
    }

    /** What the endpoint answered before the read model, for the user's current month. */
    private function liveBody(): string
    {
        $today = self::getContainer()->get(GetFinanceScores::class)->resolveDate($this->user(), null);
        $dashboard = self::getContainer()->get(GetFinanceDashboard::class)->execute(
            $this->user(),
            (int) $today->format('Y'),
            (int) $today->format('n'),
        );

        return (string) (new JsonResponse($dashboard))->getContent();
    }

    private function countRows(string $class): int
    {
        return (int) $this->em()->createQuery('SELECT COUNT(e) FROM '.$class.' e')->getSingleScalarResult();
    }

    public function testTheWholeDashboardIsReadFromTheReadModelInAFewStatements(): void
    {
        $this->drainDashboardRefreshes();

        $read = $this->read();

        self::assertLessThanOrEqual(5, \count($read['queries']), implode("\n", $read['queries']));
        self::assertNoWrite($read['queries']);
        self::assertSame(1, \count(array_filter($read['queries'], static fn (string $sql): bool => str_contains($sql, 'finance_dashboard_snapshot'))));
    }

    public function testTheStoredAnswerIsTheOldComputationByteForByte(): void
    {
        $this->drainDashboardRefreshes();
        $live = $this->liveBody();

        $read = $this->read();

        self::assertSame($live, $read['body']);
        self::assertNotNull($this->snapshot()?->getPayload());
        self::assertFalse($this->snapshot()->isStale());
        self::assertEqualsCanonicalizing(
            json_decode($live, true, 512, \JSON_THROW_ON_ERROR),
            json_decode($read['body'], true, 512, \JSON_THROW_ON_ERROR),
        );
    }

    public function testReadingWritesNothingWhileTheModelIsNotBuilt(): void
    {
        $snapshots = $this->countRows(FinanceDashboardSnapshot::class);
        $cushions = $this->countRows(SafetyCushion::class);

        $read = $this->read();

        self::assertNoWrite($read['queries']);
        self::assertSame($snapshots, $this->countRows(FinanceDashboardSnapshot::class));
        self::assertSame($cushions, $this->countRows(SafetyCushion::class));
        self::assertSame($this->liveBody(), $read['body'], 'computed live, the same answer');
    }

    public function testReadingNeverCreatesTheCushionOfAUserWhoHasNone(): void
    {
        $em = $this->em();
        $em->remove($em->find(SafetyCushion::class, $this->getFixture('cushion')->getId()));
        $em->flush();
        self::assertSame(0, $this->countRows(SafetyCushion::class));

        $read = $this->read();

        self::assertNoWrite($read['queries']);
        self::assertSame(0, $this->countRows(SafetyCushion::class));
        $data = json_decode($read['body'], true, 512, \JSON_THROW_ON_ERROR);
        self::assertSame(950000, $data['balance']['totalCents']);
    }

    public function testAWriteMakesTheModelStaleAndTheReadFollowsItWithoutWriting(): void
    {
        $this->drainDashboardRefreshes();
        self::assertFalse($this->snapshot()->isStale());
        $balance = json_decode($this->read()['body'], true, 512, \JSON_THROW_ON_ERROR)['balance']['totalCents'];
        self::assertSame(950000, $balance);

        $em = $this->em();
        $em->find(Account::class, $this->getFixture('checking')->getId())->setBalanceCents(300000);
        $em->flush();

        self::assertTrue($this->snapshot()->isStale(), 'the write said it: stale');
        $version = $this->snapshot()->getVersion();

        $read = $this->read();

        self::assertNoWrite($read['queries']);
        self::assertSame(1050000, json_decode($read['body'], true, 512, \JSON_THROW_ON_ERROR)['balance']['totalCents'], 'never the old figure');
        self::assertSame($version, $this->snapshot()->getVersion());
        self::assertSame($this->liveBody(), $read['body']);

        $this->drainDashboardRefreshes();
        self::assertFalse($this->snapshot()->isStale());
        $read = $this->read();
        self::assertLessThanOrEqual(5, \count($read['queries']));
        self::assertSame(1050000, json_decode($read['body'], true, 512, \JSON_THROW_ON_ERROR)['balance']['totalCents']);
    }

    public function testAMonthTheModelDoesNotHoldIsComputedLive(): void
    {
        $this->drainDashboardRefreshes();

        $read = $this->read('?year=2026&month=9');

        self::assertNoWrite($read['queries']);
        $data = json_decode($read['body'], true, 512, \JSON_THROW_ON_ERROR);
        self::assertSame('2026-09-30', $data['scores']['date']);
        self::assertSame('Alimentation', $data['topPosts'][0]['categoryName']);
    }

    public function testADayThatHasPassedSinceTheRebuildIsNotServed(): void
    {
        $this->drainDashboardRefreshes();
        $this->em()->getConnection()->executeStatement("UPDATE finance_dashboard_snapshot SET computed_on = '2020-01-01'");

        $today = self::getContainer()->get(GetFinanceScores::class)->resolveDate($this->user(), null);
        self::assertNull(self::getContainer()->get(FinanceDashboardSnapshotRepository::class)->fetchFreshPayload(
            $this->user(),
            (int) $today->format('Y'),
            (int) $today->format('n'),
            $today,
        ));
    }

    public function testAQualificationIsAnnouncedOnMercureOnceTheModelIsRebuilt(): void
    {
        $this->drainDashboardRefreshes();
        $this->resetMercure();

        $em = $this->em();
        $coffee = $em->find(Transaction::class, $this->getFixture('coffee')->getId());
        $coffee->setCategory($em->find(Category::class, $this->getFixture('food')->getId()));
        $em->flush();

        $snapshot = $this->snapshot();
        self::assertTrue($snapshot->isStale());
        $this->assertNothingPublishedOn('/api/finance_dashboard_snapshots/'.$snapshot->getId());

        $this->drainDashboardRefreshes();

        $topic = '/api/finance_dashboard_snapshots/'.$this->snapshot()->getId();
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertFalse($payload['stale']);
        self::assertSame(
            json_decode($this->liveBody(), true, 512, \JSON_THROW_ON_ERROR),
            $payload['dashboard'],
            'the announcement carries the dashboard',
        );
    }

    public function testEachUserHasTheirOwnModel(): void
    {
        $this->drainDashboardRefreshes();

        $this->client->request('GET', '/api/finance/dashboard');
        self::assertResponseStatusCodeSame(401);
        self::assertSame(1, $this->countRows(FinanceDashboardSnapshot::class));
    }
}
