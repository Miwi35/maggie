<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Message\DeleteTransactionCommand;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * GET /api/home/finance and its Mercure refresh (MAG-565): the same figures as
 * the Finance Dashboard, read on 2026-10-21 in the world of MAG-529.
 */
class FinanceHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/next_project.yaml', 'FinanceHomeCardTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function get(string $url, string $as = 'test_user'): array
    {
        $this->authenticateAsUser($this->getFixture($as));
        $this->client->request('GET', $url, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function topic(string $as = 'test_user'): string
    {
        return '/users/'.$this->getFixture($as)->getId().'/home/finance';
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/finance');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/finance?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testTheRingsAreTheDashboardsScores(): void
    {
        $card = $this->get('/api/home/finance');
        self::assertResponseIsSuccessful();
        $dashboard = $this->get('/api/finance/dashboard?year=2026&month=10');

        self::assertSame('finance', $card['module']);
        foreach (['week', 'month'] as $period) {
            $rhythm = $dashboard['scores'][$period]['rhythm'];
            self::assertSame([
                'status' => $rhythm['status'],
                'label' => $rhythm['label'],
                'budgetCents' => $rhythm['budgetCents'],
                'remainingCents' => $rhythm['remainingCents'],
                'expectedRemainingCents' => $rhythm['expectedRemainingCents'],
            ], $card[$period]);
        }
    }

    public function testTheMonthIsAboveTheRhythmAndTheBudgetIsTheEnvelopes(): void
    {
        $month = $this->get('/api/home/finance')['month'];

        // 200 € + 100 € of envelopes, 230 € + 128 € spent: over the budget.
        self::assertSame('above', $month['status']);
        self::assertSame('Au-dessus du rythme', $month['label']);
        self::assertSame(30000, $month['budgetCents']);
        self::assertSame(30000 - 35800, $month['remainingCents']);
    }

    public function testTheQualifyCountAndTheNextProjectAreTheDashboards(): void
    {
        $card = $this->get('/api/home/finance');
        $dashboard = $this->get('/api/finance/dashboard?year=2026&month=10');

        self::assertSame(1, $card['toQualifyCount']);
        self::assertSame($dashboard['todo']['toSortCount'], $card['toQualifyCount']);
        self::assertSame([
            'projectId' => $dashboard['nextProject']['projectId'],
            'name' => 'Vélo',
            'amountCents' => 100000,
            'gatheredCents' => 74000,
            'purchaseMonth' => '2026-10',
            'horizonMonths' => 36,
        ], $card['nextProject']);
    }

    public function testWithoutAProjectThereIsNoNextProject(): void
    {
        $card = $this->get('/api/home/finance', 'lonely_user');

        self::assertNull($card['nextProject']);
        self::assertSame(1, $card['toQualifyCount']);
        self::assertSame(0, $card['month']['budgetCents']);
    }

    public function testTheNextProjectIsShownEvenWhenNoneLandsInsideTheHorizon(): void
    {
        $card = $this->get('/api/home/finance', 'beyond_user');
        self::assertResponseIsSuccessful();

        // No income, so no capacity: both projects fall beyond the 36 months, yet the first one is the next.
        self::assertSame('Salle de bain', $card['nextProject']['name']);
        self::assertSame(900000, $card['nextProject']['amountCents']);
        self::assertSame(0, $card['nextProject']['gatheredCents']);
        self::assertNull($card['nextProject']['purchaseMonth']);
        self::assertSame(36, $card['nextProject']['horizonMonths']);
    }

    public function testTheCardSaysHowManyAccountsAndEnvelopesTheUserHas(): void
    {
        $card = $this->get('/api/home/finance');
        self::assertSame(3, $card['accountCount']);
        self::assertGreaterThan(0, $card['envelopeCount']);

        $lonely = $this->get('/api/home/finance', 'lonely_user');
        self::assertSame(1, $lonely['accountCount']);
        self::assertSame(0, $lonely['envelopeCount']);
    }

    public function testAUserWithoutAccountNorEnvelopeHasNone(): void
    {
        $card = $this->get('/api/home/finance', 'empty_user');
        self::assertResponseIsSuccessful();

        self::assertSame(0, $card['accountCount']);
        self::assertSame(0, $card['envelopeCount']);
    }

    public function testTheCardDoesNotMoveWithTheDay(): void
    {
        $today = $this->get('/api/home/finance?day=today');
        $tomorrow = $this->get('/api/home/finance?day=tomorrow');

        self::assertSame('tomorrow', $tomorrow['day']);
        unset($today['day'], $tomorrow['day']);
        self::assertSame($today, $tomorrow);
    }

    public function testQualifyingCountsOnlyTheUsersOwnLines(): void
    {
        self::assertSame(1, $this->get('/api/home/finance')['toQualifyCount']);
        self::assertSame(1, $this->get('/api/home/finance', 'lonely_user')['toQualifyCount']);
    }

    public function testChangingATransactionRefreshesTheCard(): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteTransactionCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            transactionId: (string) $this->getFixture('to_qualify')->getId(),
        ));

        $this->assertMercureUpdatePublished($this->topic());
        $this->assertNothingPublishedOn($this->topic('lonely_user'));
        self::assertSame(0, $this->get('/api/home/finance')['toQualifyCount']);
    }
}
