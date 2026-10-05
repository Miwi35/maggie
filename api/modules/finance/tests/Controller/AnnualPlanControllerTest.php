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
        self::assertSame(
            ['Matériel à arbitrer', 'Abonnement 2027 déjà payé', 'Concert 2027'],
            array_column($leisure['plannedEvents'], 'label'),
        );

        // Nothing decided for 2027: the suggestion falls back on what 2026 cost.
        $travel = $this->categoryNamed($plan, 'Voyages');
        self::assertNull($travel['lastYear']['budgetedCents']);
        self::assertSame(80000, $travel['lastYear']['consumedCents']);
        self::assertSame(0, $travel['decidedCents']);
        self::assertSame(80000, $travel['suggestedCents']);
        self::assertSame(90000, $travel['envelopeCents']);

        // A monthly envelope is not last year's annual budget.
        self::assertNull($this->categoryNamed($plan, 'Courses')['lastYear']['budgetedCents']);

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
