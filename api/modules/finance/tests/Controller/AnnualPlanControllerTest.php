<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AnnualPlanControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/annual-plan');

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function rejectedQueries(): iterable
    {
        yield 'year below the range' => ['?year=1999'];
        yield 'year above the range' => ['?year=2101'];
        yield 'year is not a number' => ['?year=bientôt'];
        yield 'a fifth quarter' => ['?period=2026-Q5'];
        yield 'a month is not a period' => ['?period=2026-04'];
        yield 'a period out of range' => ['?period=1999-Q1'];
        yield 'a period and a year' => ['?period=2026-Q2&year=2026'];
        yield 'negative threshold' => ['?thresholdCents=-1'];
        // `(int) 'abc'` is 0, which would make every debit a big expense.
        yield 'threshold is not a number' => ['?thresholdCents=beaucoup'];
    }

    #[DataProvider('rejectedQueries')]
    public function testAMalformedQueryReturns400(string $query): void
    {
        $this->signIn();

        $this->client->request('GET', '/api/finance/annual-plan'.$query, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testItReportsLastYearItsBigExpensesAndWhatIsAlreadyDecided(): void
    {
        $this->signIn();

        $plan = $this->review('?year=2027');

        self::assertSame(2027, $plan['year']);
        self::assertSame(2026, $plan['sourceYear']);
        self::assertSame(10000, $plan['thresholdCents']);

        // Biggest decision first: Voyages 80 000 and Courses 45 000 on what
        // they cost, Loisirs 18 000 on what 2027 has already decided.
        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($plan['categories'], 'categoryName'));

        $leisure = $this->categoryNamed($plan, 'Loisirs');
        // The id the client reads back, in the spelling the rest of the API
        // uses. One row per category, whatever opened it.
        self::assertSame((string) $this->getFixture('leisure')->getId(), $leisure['categoryId']);
        self::assertCount(3, $plan['categories']);
        self::assertSame(36000, $leisure['lastYear']['budgetedCents']);
        // 24 000 spent + 1 800 spent + 15 000 committed; the 50 000 credit is
        // not a cost and the 2025 festival is out of the window.
        self::assertSame(40800, $leisure['lastYear']['consumedCents']);

        // Over the threshold only, biggest first, as positive amounts.
        self::assertSame(
            [['Festival des Vieilles Charrues', 24000, 7], ['Abonnement saison', 15000, 11]],
            array_map(
                fn (array $event) => [$event['label'], $event['amountCents'], $event['month']],
                $leisure['lastYear']['events'],
            ),
        );

        // The statuses stay apart, and are named as GetBudgetStatus names them.
        self::assertSame(12000, $leisure['plannedCents']);
        self::assertSame(6000, $leisure['committedCents']);
        self::assertSame(18000, $leisure['decidedCents']);
        // Reported, and absent from everything that is summed.
        self::assertSame(4000, $leisure['toArbitrateCents']);
        self::assertSame(18000, $leisure['suggestedCents']);
        self::assertNull($leisure['envelopeId']);
        // A plan is what is still to come and what a budget has to cover: the
        // 2027 debit already paid and the 2027 credit are neither.
        self::assertSame(
            ['Matériel à arbitrer', 'Abonnement 2027 déjà payé', 'Concert 2027'],
            array_column($leisure['plannedEvents'], 'label'),
        );

        // Voyages has decided nothing for 2027, so its suggestion falls back
        // on what 2026 cost — even though an envelope is already set.
        $travel = $this->categoryNamed($plan, 'Voyages');
        self::assertSame(70000, $travel['lastYear']['budgetedCents']);
        self::assertSame(80000, $travel['lastYear']['consumedCents']);
        self::assertSame(0, $travel['decidedCents']);
        self::assertSame(80000, $travel['suggestedCents']);
        self::assertSame((string) $this->getFixture('travel_2027_annual')->getId(), $travel['envelopeId']);
        self::assertSame(90000, $travel['envelopeCents']);

        $groceries = $this->categoryNamed($plan, 'Courses');
        // A monthly envelope is not last year's annual budget.
        self::assertNull($groceries['lastYear']['budgetedCents']);

        // The currency the budget is, or will be, set in: the target year's
        // envelope first, then last year's, then what setting one defaults to.
        // Never a transaction's — the lines of one category can be in several.
        self::assertSame('EUR', $travel['currency']);
        self::assertSame('CHF', $leisure['currency']);
        self::assertSame('EUR', $groceries['currency']);

        self::assertSame(165800, $plan['totalLastYearConsumedCents']);
        self::assertSame(18000, $plan['totalDecidedCents']);
        self::assertSame(4000, $plan['totalToArbitrateCents']);
        self::assertSame(143000, $plan['totalSuggestedCents']);
        self::assertSame(90000, $plan['totalEnvelopedCents']);
    }

    public function testItLeavesOutWhatBelongsToSomebodyElse(): void
    {
        $this->signIn();

        $plan = $this->review('?year=2027');

        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($plan['categories'], 'categoryName'));
        foreach ($plan['categories'] as $category) {
            self::assertNotContains(
                'Festival du voisin',
                array_column($category['lastYear']['events'], 'label'),
            );
        }
    }

    public function testTheThresholdDecidesWhichExpensesComeBackAndNothingElse(): void
    {
        $this->signIn();

        $plan = $this->review('?year=2027&thresholdCents=20000');

        self::assertSame(
            ['Festival des Vieilles Charrues'],
            array_column($this->categoryNamed($plan, 'Loisirs')['lastYear']['events'], 'label'),
        );

        // Raised past every expense, it empties the candidate lists and moves
        // nothing else: a total that followed it would say 2026 cost less than
        // it did, and the screens read that figure as "consumed last year".
        $strict = $this->review('?year=2027&thresholdCents=100000');

        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($strict['categories'], 'categoryName'));
        self::assertSame([], $this->categoryNamed($strict, 'Courses')['lastYear']['events']);
        self::assertSame(45000, $this->categoryNamed($strict, 'Courses')['lastYear']['consumedCents']);
        self::assertSame(165800, $strict['totalLastYearConsumedCents']);
        self::assertSame(143000, $strict['totalSuggestedCents']);
    }

    public function testAQuarterIsPlannedFromTheOneBehindIt(): void
    {
        $this->loadFixtures('period_plan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $plan = $this->review('?period=2026-Q2');

        self::assertSame(['key' => '2026-Q2', 'level' => 'quarter', 'from' => '2026-04', 'to' => '2026-06', 'months' => 3], $plan['period']);
        self::assertSame('2026-Q1', $plan['sourcePeriod']['key']);
        self::assertSame(2026, $plan['year']);

        $leisure = $this->categoryNamed($plan, 'Loisirs');
        // What the quarter behind had budgeted and cost, over its three months.
        self::assertSame(60000, $leisure['lastYear']['budgetedCents']);
        self::assertSame(73000, $leisure['lastYear']['consumedCents']);
        // The trip decided for May carries the suggestion, per quarter and per month.
        self::assertSame(90000, $leisure['plannedCents']);
        self::assertSame(90000, $leisure['suggestedCents']);
        self::assertSame(30000, $leisure['suggestedMonthlyCents']);
        self::assertSame(['Voyage en Norvège'], array_column($leisure['plannedEvents'], 'label'));

        $food = $this->categoryNamed($plan, 'Alimentation');
        self::assertSame(120000, $food['lastYear']['budgetedCents']);
        // Nothing decided: what the quarter behind cost, 50 000 over three months.
        self::assertSame(50000, $food['suggestedCents']);
        self::assertSame(16667, $food['suggestedMonthlyCents']);
        // April is already set; the quarter has no single envelope to point at.
        self::assertSame(45000, $food['envelopeCents']);
        self::assertNull($food['envelopeId']);
    }

    public function testASemesterAndAYearAreReadTheSameWay(): void
    {
        $this->loadFixtures('period_plan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $semester = $this->review('?period=2026-S2');
        self::assertSame('2026-S1', $semester['sourcePeriod']['key']);
        self::assertSame(6, $semester['period']['months']);
        // The whole first quarter, the 30 000 put aside included: the session has always counted what a category cost.
        self::assertSame(153000, $semester['totalLastYearConsumedCents']);

        $year = $this->review('?period=2026');
        self::assertSame(2025, $year['sourceYear']);
        self::assertSame($this->review('?year=2026'), $year, 'a year is a year, whichever way it is asked');
    }

    public function testItDefaultsToTheYearBeingPrepared(): void
    {
        $this->signIn();

        $plan = $this->review('');

        $now = new \DateTimeImmutable();
        $expected = (int) $now->format('Y') + ((int) $now->format('n') >= 11 ? 1 : 0);
        self::assertSame($expected, $plan['year']);
    }

    private function signIn(): void
    {
        $this->loadFixtures('annual_plan.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function review(string $query): array
    {
        $this->client->request('GET', '/api/finance/annual-plan'.$query, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $plan
     *
     * @return array<string, mixed>
     */
    private function categoryNamed(array $plan, string $name): array
    {
        foreach ($plan['categories'] as $category) {
            if ($name === $category['categoryName']) {
                return $category;
            }
        }

        self::fail("No category named {$name} in the plan.");
    }
}
