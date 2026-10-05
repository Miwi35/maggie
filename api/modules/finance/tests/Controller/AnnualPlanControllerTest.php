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
use Symfony\Component\Uid\Ulid;

class AnnualPlanControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    /** What the fixture already holds, so a count can say "and nothing more". */
    private const SEEDED_ENVELOPES = 4;
    private const SEEDED_TRANSACTIONS = 12;

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
    public function testReviewRejectsAMalformedQuery(string $query): void
    {
        $this->signIn();

        $this->client->request('GET', '/api/finance/annual-plan'.$query, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReviewReportsLastYearItsBigExpensesAndWhatIsAlreadyDecided(): void
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
        // The id the client posts back, in the spelling the rest of the API
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

    public function testThresholdDecidesWhichExpensesComeBackAndNothingElse(): void
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
        // The session claims nothing about the owner's everyday lifestyle.
        self::assertFalse($transaction->isExceptional());
        // The currency falls back on the account's, not on a hardcoded EUR.
        self::assertSame($account->getCurrency(), $transaction->getCurrency());

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

    public function testApplyMarksAnEventExceptionalWhenAsked(): void
    {
        $this->signIn();

        $applied = $this->apply([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Ordinateur 2027',
                'amountCents' => 150000,
                'month' => 3,
                'isExceptional' => true,
            ]],
        ]);

        self::assertTrue($applied['events'][0]['isExceptional']);
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
        self::assertCount(self::SEEDED_ENVELOPES, $em->getRepository(Envelope::class)->findAll());
    }

    public function testApplyBudgetsTheSameCategoryTwiceWithoutDuplicatingIt(): void
    {
        $this->signIn();
        $leisure = (string) $this->getFixture('leisure')->getId();

        // One body naming a category twice: the second line must see what the
        // first wrote, not try to create a second annual envelope.
        $applied = $this->apply([
            'year' => 2027,
            'envelopes' => [
                ['categoryId' => $leisure, 'amountCents' => 50000],
                ['categoryId' => $leisure, 'amountCents' => 60000],
            ],
        ]);

        self::assertSame(1, $applied['envelopesCreated']);
        self::assertSame(1, $applied['envelopesUpdated']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(self::SEEDED_ENVELOPES + 1, $em->getRepository(Envelope::class)->findAll());
        self::assertSame(60000, $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture('leisure')->getId(),
            'mode' => BudgetMode::Annual,
            'year' => 2027,
        ])->getAmountCents());
    }

    public function testARejectedPlanWritesNothingAtAll(): void
    {
        $this->signIn();

        // A valid event, then an envelope on somebody else's category. The
        // commands persist one at a time, so an apply that dispatched as it
        // read would leave the festival behind — and the retry that follows
        // would plan it twice.
        $this->postPlan([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Festival fantôme',
                'amountCents' => 26000,
                'month' => 7,
            ]],
            'envelopes' => [[
                'categoryId' => (string) $this->getFixture('other_leisure')->getId(),
                'amountCents' => 30000,
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Transaction::class)->findOneBy(['label' => 'Festival fantôme']));
        self::assertCount(self::SEEDED_TRANSACTIONS, $em->getRepository(Transaction::class)->findAll());
        self::assertCount(self::SEEDED_ENVELOPES, $em->getRepository(Envelope::class)->findAll());
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function rejectedPlans(): iterable
    {
        yield 'no year' => [['envelopes' => []]];
        yield 'year out of range' => [['year' => 1999, 'envelopes' => []]];
        yield 'year is not an integer' => [['year' => '2027', 'envelopes' => []]];
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

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function rejectedEvents(): iterable
    {
        yield 'no label' => [['amountCents' => 1000], 'label'];
        yield 'nothing at zero' => [['label' => 'Gratuit', 'amountCents' => 0], 'amountCents'];
        yield 'a negative amount' => [['label' => 'À l\'envers', 'amountCents' => -1000], 'amountCents'];
        yield 'a month out of range' => [['label' => 'Treizième', 'amountCents' => 1000, 'month' => 13], 'month'];
        // You do not plan what has already gone out.
        yield 'already spent' => [['label' => 'Déjà payé', 'amountCents' => 1000, 'status' => 'spent'], 'status'];
        yield 'a status that is not one' => [['label' => 'Peut-être', 'amountCents' => 1000, 'status' => 'maybe'], 'status'];
        // Three characters wide in the database: unchecked, it would be a 500.
        yield 'a currency that is not a code' => [['label' => 'Euros', 'amountCents' => 1000, 'currency' => 'EURO'], 'currency'];
    }

    /**
     * @param array<string, mixed> $event
     */
    #[DataProvider('rejectedEvents')]
    public function testApplyRejectsAMalformedEvent(array $event, string $expectedField): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('checking')->getId(),
            'events' => [['categoryId' => (string) $this->getFixture('leisure')->getId()] + $event],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($expectedField, $this->errorMessage());
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

    public function testApplyRefusesAnotherUsersAccount(): void
    {
        $this->signIn();

        // The one line between a conversational tool and a planned debit in a
        // stranger's account.
        $this->postPlan([
            'year' => 2027,
            'accountId' => (string) $this->getFixture('other_checking')->getId(),
            'events' => [[
                'categoryId' => (string) $this->getFixture('leisure')->getId(),
                'label' => 'Chez le voisin',
                'amountCents' => 1000,
            ]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('account not found', $this->errorMessage());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Transaction::class)->findOneBy(['label' => 'Chez le voisin']));
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
        self::assertCount(self::SEEDED_ENVELOPES, $em->getRepository(Envelope::class)->findAll());
    }

    public function testApplyRefusesACategoryThatDoesNotExist(): void
    {
        $this->signIn();

        $this->postPlan([
            'year' => 2027,
            'envelopes' => [['categoryId' => (string) new Ulid(), 'amountCents' => 1000]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('category not found', $this->errorMessage());
    }

    public function testApplyRefusesACategoryNameInPlaceOfAnId(): void
    {
        $this->signIn();

        // The likeliest mistake with a conversational tool. Doctrine answers a
        // non-ULID with a conversion error, so unchecked this is a 500.
        $this->postPlan([
            'year' => 2027,
            'envelopes' => [['categoryId' => 'Loisirs', 'amountCents' => 1000]],
        ]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('category not found', $this->errorMessage());
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
