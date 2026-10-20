<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The bilan of the period an augmented review closes (MAG-462). */
class PeriodBalanceControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const string ENDPOINT = '/api/finance/review/period-balance';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    private function login(): void
    {
        $this->loadFixtures('period_plan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function balance(string $month): array
    {
        $this->client->request('GET', self::ENDPOINT.'?month='.$month, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT.'?month=2026-03');

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function rejectedMonths(): iterable
    {
        yield 'no month' => [''];
        yield 'a month that closes nothing' => ['?month=2026-04'];
        yield 'month 13' => ['?month=2026-13'];
        yield 'no dash' => ['?month=202603'];
        yield 'year out of range' => ['?month=1999-03'];
        yield 'garbage' => ['?month=mars'];
    }

    #[DataProvider('rejectedMonths')]
    public function testAMonthThatClosesNoPeriodReturns400(string $query): void
    {
        $this->login();

        $this->client->request('GET', self::ENDPOINT.$query, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testAQuarterIsReadMonthByMonth(): void
    {
        $this->login();

        $balance = $this->balance('2026-03');

        self::assertSame(['key' => '2026-Q1', 'level' => 'quarter', 'from' => '2026-01', 'to' => '2026-03', 'months' => 3], $balance['period']);
        self::assertSame(
            [['2026-01', 60000, 35000, 0], ['2026-02', 60000, 38000, 30000], ['2026-03', 60000, 50000, 0]],
            array_map(
                static fn (array $month): array => [$month['month'], $month['budgetCents'], $month['spentCents'], $month['savedCents']],
                $balance['months'],
            ),
        );
        self::assertSame(['budgetCents' => 180000, 'spentCents' => 123000, 'savedCents' => 30000], $balance['totals']);
    }

    public function testEachEnvelopeIsReadOverTheWholePeriod(): void
    {
        $this->login();

        $envelopes = array_column($this->balance('2026-03')['envelopes'], null, 'name');

        self::assertSame(['Alimentation', 'Loisirs'], array_keys($envelopes));
        self::assertSame(['held', 120000, 50000, 41], [$envelopes['Alimentation']['status'], $envelopes['Alimentation']['budgetCents'], $envelopes['Alimentation']['consumedCents'], $envelopes['Alimentation']['percent']]);
        self::assertSame(['over', 60000, 73000, 121], [$envelopes['Loisirs']['status'], $envelopes['Loisirs']['budgetCents'], $envelopes['Loisirs']['consumedCents'], $envelopes['Loisirs']['percent']]);
    }

    public function testSatisfactionAndSavingsCoverThePeriod(): void
    {
        $this->login();

        $balance = $this->balance('2026-03');

        // 15 000 kept and 50 000 avoidable; the committed gift is not judged yet.
        self::assertSame(
            ['judgedCents' => 65000, 'keptCents' => 15000, 'avoidableCents' => 50000, 'keptPercent' => 23],
            $balance['satisfaction'],
        );
        self::assertSame(30000, $balance['savings']['savedCents']);
        self::assertNull($balance['savings']['plannedCents'], 'no income known, nothing to compare with');
    }

    public function testPlannedExpensesAreDoneOrPostponed(): void
    {
        $this->login();

        $projects = $this->balance('2026-03')['projects'];

        self::assertSame(['Cadeau'], array_column($projects['done'], 'label'));
        self::assertSame(8000, $projects['done'][0]['amountCents']);
        // Still planned though their month has passed; the May trip is outside the quarter.
        self::assertSame(['Vélo', 'Dentiste'], array_column($projects['postponed'], 'label'));
    }

    public function testItLeavesOutWhatBelongsToSomebodyElse(): void
    {
        $this->login();

        $balance = $this->balance('2026-03');

        self::assertSame(123000, $balance['totals']['spentCents']);
        self::assertSame(['Alimentation', 'Loisirs'], array_column($balance['envelopes'], 'name'));
    }

    public function testDecemberReadsTheWholeYear(): void
    {
        $this->login();

        $balance = $this->balance('2026-12');

        self::assertSame('2026', $balance['period']['key']);
        self::assertCount(12, $balance['months']);
        self::assertSame(123000, $balance['totals']['spentCents']);
    }

    public function testJuneReadsTheSemester(): void
    {
        $this->login();

        $balance = $this->balance('2026-06');

        self::assertSame('2026-S1', $balance['period']['key']);
        self::assertCount(6, $balance['months']);
    }
}
