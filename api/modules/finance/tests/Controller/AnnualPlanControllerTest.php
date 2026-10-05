<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AnnualPlanControllerTest extends WebTestCase
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
    }

    public function testUnauthenticatedReviewReturns401(): void
    {
        $this->client->request('GET', '/api/finance/annual-plan');

        self::assertResponseStatusCodeSame(401);
    }

    public function testUnauthenticatedApplyReturns401(): void
    {
        $this->client->request('POST', '/api/finance/annual-plan');

        self::assertResponseStatusCodeSame(401);
    }

    public function testYearOutOfRangeReturns400(): void
    {
        $this->signIn();

        $this->client->request('GET', '/api/finance/annual-plan?year=1999', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testNegativeThresholdReturns400(): void
    {
        $this->signIn();

        $this->client->request('GET', '/api/finance/annual-plan?thresholdCents=-1', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReviewReportsLastYearItsBigExpensesAndWhatIsAlreadyPlanned(): void
    {
        $this->signIn();

        $plan = $this->review('?year=2027');

        self::assertSame(2027, $plan['year']);
        self::assertSame(2026, $plan['sourceYear']);
        self::assertSame(10000, $plan['thresholdCents']);

        // Biggest decision first: Voyages 80 000, Courses 45 000, Loisirs
        // 12 000 (already planned, so the planned total wins over last year's).
        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($plan['categories'], 'categoryName'));

        $leisure = $this->categoryNamed($plan, 'Loisirs');
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

        // Planned counts, to-arbitrate is reported apart and never summed.
        self::assertSame(12000, $leisure['plannedCents']);
        self::assertSame(4000, $leisure['toArbitrateCents']);
        self::assertSame(12000, $leisure['suggestedCents']);
        self::assertNull($leisure['envelopeId']);
        self::assertSame(
            ['Matériel à arbitrer', 'Concert 2027'],
            array_column($leisure['plannedEvents'], 'label'),
        );

        // Nothing planned for 2027: the suggestion falls back on what 2026 cost.
        $travel = $this->categoryNamed($plan, 'Voyages');
        self::assertNull($travel['lastYear']['budgetedCents']);
        self::assertSame(80000, $travel['lastYear']['consumedCents']);
        self::assertSame(80000, $travel['suggestedCents']);
        self::assertSame(90000, $travel['envelopeCents']);

        // A monthly envelope is not last year's annual budget.
        self::assertNull($this->categoryNamed($plan, 'Courses')['lastYear']['budgetedCents']);

        self::assertSame(165800, $plan['totalLastYearConsumedCents']);
        self::assertSame(12000, $plan['totalPlannedCents']);
        self::assertSame(137000, $plan['totalSuggestedCents']);
        self::assertSame(90000, $plan['totalEnvelopedCents']);
    }

    public function testReviewLeavesOutWhatBelongsToSomebodyElse(): void
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

    public function testThresholdDecidesWhichExpensesComeBack(): void
    {
        $this->signIn();

        $plan = $this->review('?year=2027&thresholdCents=20000');

        self::assertSame(
            ['Festival des Vieilles Charrues'],
            array_column($this->categoryNamed($plan, 'Loisirs')['lastYear']['events'], 'label'),
        );
    }

    public function testReviewDefaultsToTheYearBeingPrepared(): void
    {
        $this->signIn();

        $plan = $this->review('');

        $now = new \DateTimeImmutable();
        $expected = (int) $now->format('Y') + ((int) $now->format('n') >= 11 ? 1 : 0);
        self::assertSame($expected, $plan['year']);
    }

    public function testApplyPlansTheEventsAndBudgetsTheEnvelopes(): void
    {
        $this->signIn();
        $leisure = $this->getFixture('leisure');
        $account = $this->getFixture('checking');

        $applied = $this->apply([
            'year' => 2027,
            'accountId' => (string) $account->getId(),
            'events' => [[
                'categoryId' => (string) $leisure->getId(),
                'label' => 'Festival 2027',
                'amountCents' => 26000,
                'month' => 7,
            ]],
            'envelopes' => [[
                'categoryId' => (string) $leisure->getId(),
                'amountCents' => 50000,
            ]],
        ]);

        self::assertTrue($applied['success']);
        self::assertSame(1, $applied['eventsCreated']);
        self::assertSame(1, $applied['envelopesCreated']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        // A positive amount in, a debit on the books, on the 1st of the month.
        $transaction = $em->getRepository(Transaction::class)->findOneBy(['label' => 'Festival 2027']);
        self::assertNotNull($transaction);
        self::assertSame(-26000, $transaction->getAmountCents());
        self::assertSame('2027-07-01', $transaction->getBookedAt()->format('Y-m-d'));
        self::assertSame(TransactionStatus::Planned, $transaction->getStatus());
        self::assertSame((string) $leisure->getId(), (string) $transaction->getCategory()->getId());

        $envelope = $em->getRepository(Envelope::class)->findOneBy([
            'category' => $leisure->getId(),
            'mode' => BudgetMode::Annual,
            'year' => 2027,
        ]);
        self::assertNotNull($envelope);
        self::assertSame(50000, $envelope->getAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testApplyOverwritesAnEnvelopeAlreadySetAndThenLeavesItAlone(): void
    {
        $this->signIn();
        $travel = $this->getFixture('travel');
        $existing = $this->getFixture('travel_2027_annual');

        // The session is where the amount is decided, so it writes over the
        // 90 000 already there — unlike a roll-over, which never would.
        $applied = $this->apply([
            'year' => 2027,
            'envelopes' => [['categoryId' => (string) $travel->getId(), 'amountCents' => 120000]],
        ]);

        self::assertSame(1, $applied['envelopesUpdated']);
        self::assertSame((string) $existing->getId(), $applied['envelopes'][0]['id']);

        // Replayed with the same amount, it writes nothing.
        $again = $this->apply([
            'year' => 2027,
            'envelopes' => [['categoryId' => (string) $travel->getId(), 'amountCents' => 120000]],
        ]);

        self::assertSame(1, $again['envelopesUnchanged']);
        self::assertSame(0, $again['envelopesUpdated']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(120000, $em->find(Envelope::class, $existing->getId())->getAmountCents());
        self::assertCount(4, $em->getRepository(Envelope::class)->findAll());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function rejectedPlans(): iterable
    {
        yield 'no year' => [['envelopes' => []]];
        yield 'year out of range' => [['year' => 1999, 'envelopes' => []]];
        yield 'events is not a list' => [['year' => 2027, 'events' => 'nope']];
        yield 'event is not an object' => [['year' => 2027, 'events' => ['nope']]];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('rejectedPlans')]
    public function testApplyRejectsAMalformedPlan(array $body): void
    {
        $this->signIn();

        $this->postPlan($body);

        self::assertResponseStatusCodeSame(400);
    }

    public function testApplyRejectsANonPositiveAmount(): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Gratuit',
                'amountCents' => 0,
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('amountCents', $this->errorMessage());
    }

    public function testApplyRefusesToPlanSomethingAlreadySpent(): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Déjà payé',
                'amountCents' => 1000,
                'status' => 'spent',
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('status', $this->errorMessage());
    }

    public function testApplyNeedsAnAccountForAnEvent(): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Sans compte',
                'amountCents' => 1000,
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('accountId', $this->errorMessage());
    }

    public function testApplyRefusesAnotherUsersCategory(): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'envelopes' => [[
                'categoryId' => (string) $this->getFixture('other_leisure')->getId(),
                'amountCents' => 1000,
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('category not found', $this->errorMessage());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(4, $em->getRepository(Envelope::class)->findAll());
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

        return $this->payload();
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function apply(array $body): array
    {
        $this->postPlan($body);

        self::assertResponseIsSuccessful();

        return $this->payload();
    }

    /** @param array<string, mixed> $body */
    private function postPlan(array $body): void
    {
        $this->client->request(
            'POST',
            '/api/finance/annual-plan',
            [],
            [],
            array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()),
            json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function payload(): array
    {
        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function errorMessage(): string
    {
        return (string) $this->payload()['error'];
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
