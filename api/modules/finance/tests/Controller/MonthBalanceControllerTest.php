<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class MonthBalanceControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    /** @return array<string, mixed> */
    private function balance(string $month): array
    {
        $this->client->request('GET', '/api/finance/month-balance?month='.$month, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures('month_balance.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/month-balance?month=2026-09');

        self::assertResponseStatusCodeSame(401);
    }

    #[DataProvider('badMonths')]
    public function testABadMonthReturns400(string $month): void
    {
        $this->login();

        $this->client->request('GET', '/api/finance/month-balance?month='.$month, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{string}> */
    public static function badMonths(): iterable
    {
        yield 'month 13' => ['2026-13'];
        yield 'no dash' => ['202609'];
        yield 'year out of range' => ['1999-09'];
        yield 'garbage' => ['september'];
    }

    public function testEachEnvelopeIsHeldCloseOrOver(): void
    {
        $this->login();

        $envelopes = array_column($this->balance('2026-09')['envelopes'], null, 'name');

        self::assertSame(['Alimentation', 'Loisirs', 'Transport'], array_keys($envelopes));
        self::assertSame('held', $envelopes['Alimentation']['status']);
        self::assertSame(50, $envelopes['Alimentation']['percent']);
        self::assertSame('close', $envelopes['Loisirs']['status']);
        self::assertSame(85, $envelopes['Loisirs']['percent']);
        self::assertSame('over', $envelopes['Transport']['status']);
        self::assertSame(150, $envelopes['Transport']['percent']);
        self::assertSame(15000, $envelopes['Transport']['consumedCents']);
        self::assertSame(10000, $envelopes['Transport']['budgetCents']);
    }

    public function testThePastMonthIsDrawnWhole(): void
    {
        $this->login();

        $balance = $this->balance('2026-09');

        self::assertSame('2026-09', $balance['month']);
        self::assertFalse($balance['isCurrentMonth']);
        // 200 + 170 + 150 € spent in the envelopes against 700 € of budgets, read to the month's last day.
        // The unqualified purchase and the saving belong to no envelope.
        self::assertSame(70000, $balance['spending']['rhythm']['budgetCents']);
        self::assertSame(52000, $balance['spending']['rhythm']['spentCents']);
        self::assertCount(30, $balance['spending']['rhythm']['curve']);
    }

    public function testSatisfactionIsTheKeptShareOfWhatWasJudged(): void
    {
        $this->login();

        $satisfaction = $this->balance('2026-09')['satisfaction'];

        self::assertSame(17000, $satisfaction['judgedCents']);
        self::assertSame(12000, $satisfaction['keptCents']);
        self::assertSame(5000, $satisfaction['avoidableCents']);
        self::assertSame(71, $satisfaction['keptPercent']);
        // The taxi, the saving and the unqualified purchase wait for a verdict: the monthly review offers them all.
        self::assertSame(3, $satisfaction['unratedCount']);
    }

    public function testWhatWasPutAsideIsReadOffTheQualification(): void
    {
        $this->login();

        $savings = $this->balance('2026-09')['savings'];

        self::assertSame(30000, $savings['savedCents']);
        // No income is known in the fixture: nothing to plan against, never a 0 €.
        self::assertNull($savings['plannedCents']);
    }

    public function testTheUnqualifiedLinesOfTheMonthAreReportedApart(): void
    {
        $this->login();

        self::assertSame(['count' => 1, 'cents' => 4000], $this->balance('2026-09')['toQualify']);
        self::assertSame(['count' => 1, 'cents' => 9000], $this->balance('2026-08')['toQualify']);
    }

    public function testAnEmptyMonthHasNoEnvelopeNoScoreAndNothingToQualify(): void
    {
        $this->login();

        $balance = $this->balance('2026-01');

        self::assertSame([], $balance['envelopes']);
        self::assertNull($balance['satisfaction']['keptPercent']);
        self::assertSame(0, $balance['satisfaction']['judgedCents']);
        self::assertSame(0, $balance['savings']['savedCents']);
        self::assertSame(['count' => 0, 'cents' => 0], $balance['toQualify']);
    }

    public function testTheCurrentMonthIsTheDefault(): void
    {
        $this->login();

        $this->client->request('GET', '/api/finance/month-balance', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $balance = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($balance['isCurrentMonth']);
        self::assertSame((new \DateTimeImmutable('today'))->format('Y-m'), $balance['month']);
    }
}
