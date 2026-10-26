<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Routing\RouteLeg;
use Maggie\Calendar\Service\RewardGranter;
use Maggie\Calendar\Service\RewardMessages;
use Maggie\Calendar\Service\TripEstimate;
use Maggie\Calendar\Service\TripOrigin;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Tests\UseCase\RecurringProjectionsWorld;
use Maggie\Finance\UseCase\GetAccountTimeline;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Maggie\Finance\UseCase\GetPeriodBalance;
use Maggie\Finance\UseCase\GetRecurringOperations;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Maggie\Finance\UseCase\PlanningPeriod;
use Maggie\Finance\UseCase\SuggestRecurringOperations;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Entity\PurchasedItem;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * Records real API responses into contract/responses/, for the mobile DTO
 * tests to deserialise.
 *
 * The mobile DTOs are hand-written Kotlin data classes with a default on
 * nearly every field, so kotlinx.serialization does not complain when one is
 * missing — it fills the default and the screen shows a value the user never
 * chose. Testing them against JSON written by hand only proves the hand was
 * consistent with itself. Testing them against what the API actually emits is
 * the point.
 *
 * So these responses are not fixtures in the usual sense: they are recordings.
 * The API produces them, this test writes them down, DtoContractTest on the
 * mobile side reads them back. When the API changes shape, the recording
 * changes in the same commit and the Kotlin test fails there and then,
 * without a stack, an emulator or a network.
 *
 * One limit, worth knowing before trusting a recording: the suite runs with
 * no Elasticsearch, so ElasticsearchCollectionProvider takes its `test`
 * short-circuit and every collection here is the Doctrine serialisation. The
 * two paths hydrate the same entities through the same serializer, so the
 * field names these tests are about are the same — but a difference that
 * lived only in the Elasticsearch hydrator would not show up here.
 *
 * Regenerate with UPDATE_CONTRACT=1 — see ContractSnapshotTrait.
 */
final class RecordedResponseContractTest extends WebTestCase
{
    use ContractSnapshotTrait;
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use RecurringProjectionsWorld;

    private EntityManagerInterface $em;

    /**
     * Recording name => path. One per DTO the mobile app deserialises from a
     * collection, plus the identifier-less items and the finance reads.
     *
     * @var array<string, string>
     */
    private const RECORDINGS = [
        'events.collection' => '/api/events',
        'tasks.collection' => '/api/tasks',
        'tasks.view_today' => '/api/tasks/view/today',
        'tasks.view_done' => '/api/tasks/view/done',
        'agendas.collection' => '/api/agendas',
        'recipes.collection' => '/api/recipes',
        'utensils.collection' => '/api/utensils',
        'meals.collection' => '/api/meals',
        'cooking_timers.collection' => '/api/cooking/timers',
        'proactions.collection' => '/api/proactions',
        'ingredients.collection' => '/api/ingredients',
        'products.collection' => '/api/products',
        'stores.collection' => '/api/stores',
        'aisles.collection' => '/api/aisles',
        'grocery_lists.collection' => '/api/grocery_lists',
        'errands.collection' => '/api/errands',
        'recurring_grocery_items.collection' => '/api/recurring_grocery_items',
        'notifications.collection' => '/api/notifications',
        'accounts.collection' => '/api/accounts',
        'categories.collection' => '/api/categories',
        'transactions.collection' => '/api/transactions',
        'recurring_operations.collection' => '/api/recurring_operations',
        'users.me' => '/api/users/me',
        'user_preferences.me' => '/api/user_preferences/me',
        'task_settings.me' => '/api/task-settings/me',
        'task_settings.preview' => '/api/task-settings/me/preview',
        'meal_settings.me' => '/api/meal-settings/me',
        'finance_monthly_review.read' => '/api/finance/monthly-review?year=2026&month=3',
        'finance_scores.read' => '/api/finance/scores?date=2026-03-15',
        'finance_cushion_status.read' => '/api/finance/cushion-status',
        'finance_dashboard.read' => '/api/finance/dashboard?year=2026&month=3',
        'envelopes.collection' => '/api/envelopes',
        'finance_budget_status.read' => '/api/finance/budget-status?year=2026&month=3',
        'contacts.collection' => '/api/contacts',
        'contact_counterparties.collection' => '/api/contact_counterparties',
        'peer_debts.collection' => '/api/peer_debts',
        'finance_peer_debts_summary.read' => '/api/finance/peer-debts/summary',
        'finance_peer_debts.read' => '/api/finance/peer-debts',
        'finance_projects.collection' => '/api/finance/projects',
        'finance_placements.collection' => '/api/finance/placements',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // A task's `overdueDays` moves with the day (MAG-405): read at a fixed one.
        Clock::set(new MockClock('2026-04-22 12:00:00 UTC'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testTheRecordingsAreCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        foreach (self::RECORDINGS as $name => $path) {
            $this->client->request('GET', $path, [], [], array_merge(
                ['HTTP_ACCEPT' => 'application/ld+json'],
                $this->authHeaders(),
            ));

            self::assertResponseIsSuccessful(sprintf('GET %s failed, so it cannot be recorded.', $path));

            $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($body);

            $this->assertMatchesContract(
                'responses/'.$name.'.json',
                self::stabilise($body),
                sprintf('GET %s no longer returns what it used to. The mobile DTOs are written against this response; check mobile/app/src/main/java/com/maggie/app/data/model/ before regenerating.', $path),
            );
        }
    }

    /**
     * The overview is read at a fixed day: its next due dates and its overdue
     * days move with the clock, and a recording that moved every morning would
     * be noise. Same JSON as the controller sends, produced by the same use case.
     */
    public function testTheOverviewRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);

        $overview = self::getContainer()->get(GetRecurringOperations::class)->execute($user, new \DateTimeImmutable('2026-04-22'));
        $body = json_decode((string) json_encode($overview, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);

        $this->assertMatchesContract(
            'responses/recurring_operations.overview.json',
            self::stabilise($body),
            'GET /api/finance/recurring-operations/overview no longer returns what it used to. The mobile DTOs (RecurringOperation.kt) are written against this response.',
        );
    }

    /**
     * The treasury and the annual plan project the series' occurrences
     * (MAG-307): read at a fixed day and year, as the controllers send them,
     * so the recording does not move with the clock. The outlook is read on
     * 2026-04-30 with April's salary still awaited, so it runs to the 24th of
     * May and holds the rent expected on the 5th.
     */
    public function testTheProjectionsRecordingsAreCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedTreasury.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);

        $outlook = self::getContainer()->get(GetTreasuryOutlook::class)->execute($user, new \DateTimeImmutable('2026-04-30'));
        $sources = array_merge(...array_map(static fn (array $row): array => array_column($row['movements'], 'source'), $outlook['projection']));
        self::assertContains('recurring', $sources, 'The recorded treasury holds no expected occurrence, so it proves nothing about `source`.');

        $plan = self::getContainer()->get(GetAnnualPlan::class)->execute($user, 2026);
        self::assertNotSame([], array_filter(array_column($plan['categories'], 'recurringCents')), 'The recorded plan holds no recurring debit.');

        foreach (['finance_treasury_outlook.read' => $outlook, 'finance_annual_plan.read' => $plan] as $name => $read) {
            $body = json_decode((string) json_encode($read, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($body);

            $this->assertMatchesContract(
                'responses/'.$name.'.json',
                self::stabilise($body),
                sprintf('The %s read no longer returns what it used to.', $name),
            );
        }
    }

    /**
     * The reviews that close a quarter (MAG-462): the bilan of the first quarter
     * of 2026, and the plan of the second, as the controllers send them.
     */
    public function testThePeriodReviewRecordingsAreCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);

        $balance = self::getContainer()->get(GetPeriodBalance::class)->execute($user, PlanningPeriod::parse('2026-Q1'));
        self::assertNotEmpty($balance['envelopes'], 'The recorded period balance holds no envelope.');
        $plan = self::getContainer()->get(GetAnnualPlan::class)->execute($user, PlanningPeriod::parse('2026-Q2'));
        self::assertNotEmpty($plan['categories'], 'The recorded period plan holds no category.');

        foreach (['finance_period_balance.read' => $balance, 'finance_period_plan.read' => $plan] as $name => $read) {
            $body = json_decode((string) json_encode($read, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($body);

            $this->assertMatchesContract(
                'responses/'.$name.'.json',
                self::stabilise($body),
                sprintf('The %s read no longer returns what it used to. The mobile DTOs (ReviewActivity.kt) and the admin hooks (usePeriodBalance, usePeriodPlan) read it.', $name),
            );
        }
    }

    /**
     * The « À trier » pile (MAG-423), as the controller sends it: one line a
     * rule claims — with its reason — and one nothing claims.
     */
    public function testTheToSortRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedToSort.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/transactions/to-sort', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertSame('rule', $body['transactions'][0]['suggestion']['source'] ?? null, 'The recorded pile holds no suggested line, so the recording proves nothing about a suggestion.');
        self::assertNull($body['transactions'][1]['suggestion'], 'The recorded pile holds no line without suggestion.');
        self::assertTrue($body['transactions'][2]['asksForContact'] ?? null, 'The recorded pile holds no transfer asking for its contact.');

        $this->assertMatchesContract(
            'responses/finance_transactions_to_sort.read.json',
            self::stabilise($body),
            'GET /api/finance/transactions/to-sort no longer returns what it used to. The mobile DTOs (ToSort.kt) are written against this response.',
        );
    }

    /**
     * The review's queue (MAG-458): the lines nothing recognised first, then
     * the ones an operation recognised in the month.
     */
    public function testTheReviewQueueRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedToSort.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/review/queue?month=2026-03', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertSame([false, false, false, true], array_column($body['items'], 'recognised'), 'The recorded queue must hold three lines to qualify and one recognised.');

        $this->assertMatchesContract(
            'responses/finance_review_queue.read.json',
            self::stabilise($body),
            'GET /api/finance/review/queue no longer returns what it used to. The mobile DTOs (ReviewQueue.kt) and the admin hook read this response.',
        );
    }

    /**
     * Where the user stands in the review of a month (MAG-456): a step and the
     * transactions already handled, as the PUT leaves them and the GET reads them.
     */
    public function testTheReviewProgressRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedToSort.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('PUT', '/api/finance/review/progress', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode([
            'month' => '2026-03',
            'step' => 'balance',
            'handledTransactionIds' => [(string) $this->getFixture('recorded_to_sort_claimed')->getId()],
        ], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/finance/review/progress?month=2026-03', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(1, $body['handledTransactionIds'], 'The recorded progress must hold a handled transaction.');

        $this->assertMatchesContract(
            'responses/finance_review_progress.read.json',
            self::stabilise($body),
            'GET /api/finance/review/progress no longer returns what it used to. The mobile DTOs (ReviewActivity.kt) and the admin hook (useReviewProgress) read this response.',
        );
    }

    /**
     * The activities in progress (MAG-613), as `/api/activity-status` lists
     * them once a Cuisine session is saved: the item the PUT and the GET of
     * `/api/activity-status/{activity}` send, inside `member`.
     */
    public function testTheActivityStatusRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $recipeId = '01JB0000000000000000000001';
        $this->client->request('PUT', '/api/activity-status/cuisine', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode([
            'origin' => ['kind' => 'recipe', 'id' => $recipeId],
            'state' => [
                'queue' => [['recipeId' => $recipeId, 'servings' => 4]],
                'recipeIndex' => 0,
                'step' => 2,
                'doneSteps' => [$recipeId => [1]],
                'guests' => 4,
                'phase' => 'cooking',
                'startedAt' => 1785000000000,
            ],
        ], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);

        $this->client->request('GET', '/api/activity-status', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(1, $body['member'], 'The recording must hold the saved activity.');

        $this->assertMatchesContract(
            'responses/activity_status.collection.json',
            self::stabilise($body),
            'GET /api/activity-status no longer returns what it used to. The clients that resume an activity (Cuisine) read this response.',
        );
    }

    public function testTheEnvelopeAdjustmentsRecordingIsCurrent(): void
    {
        $this->loadFixtures(\dirname(__DIR__, 2).'/modules/finance/tests/Controller/fixtures/envelope_adjustments.yaml');

        $user = $this->getFixture('test_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/envelope-adjustments?month=2026-09', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty(array_filter(array_column($body['envelopes'], 'suggestion')), 'The recorded world must hold an envelope with a suggestion.');
        self::assertNotEmpty(array_filter(array_column(array_filter(array_column($body['envelopes'], 'suggestion')), 'seasonal')), 'The recorded world must hold a seasonal suggestion.');

        $this->assertMatchesContract(
            'responses/finance_envelope_adjustments.read.json',
            self::stabilise($body),
            'GET /api/finance/envelope-adjustments no longer returns what it used to. The mobile DTOs (EnvelopeAdjustment.kt) and the admin hook read this response.',
        );
    }

    /**
     * « À venir » (MAG-463), as the controller sends it, read on 2026-10-16:
     * a weekly series in the week, a rent, a salary and an insurance in
     * November, envelopes written, rolled over and overcommitted.
     */
    public function testTheUpcomingRecordingIsCurrent(): void
    {
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(\dirname(__DIR__, 2).'/modules/finance/tests/UseCase/fixtures/upcoming_projection.yaml');
        $this->series('Marché', -2000, '2026-10-03', categoryRef: 'groceries', period: RecurrencePeriod::Weekly);
        $this->series('Loyer', -90000, '2026-07-05');
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');
        $this->series('Assurance auto', -1200, '2026-07-12', categoryRef: 'insurance');

        $user = $this->getFixture('test_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/upcoming?date=2026-10-16', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty($body['week']['expenses'], 'The recorded week holds no expense.');
        self::assertNotEmpty($body['month']['incomes'], 'The recorded month holds no income.');
        self::assertNotEmpty(array_filter($body['month']['envelopes'], static fn (array $line): bool => null === $line['envelopeId']), 'The recorded month holds no rolled-over envelope.');
        self::assertNotEmpty(array_filter($body['month']['envelopes'], static fn (array $line): bool => $line['isOvercommitted']), 'The recorded month holds no overcommitted envelope.');

        $this->assertMatchesContract(
            'responses/finance_upcoming.read.json',
            self::stabilise($body),
            'GET /api/finance/upcoming no longer returns what it used to. The mobile DTOs (UpcomingProjection.kt) and the admin hook read this response.',
        );
    }

    /**
     * The projects over 36 months (MAG-526), read on 2026-10-21 with a fixed
     * date, a dependency, a projected loan and a missed deadline all present.
     */
    public function testTheProjectPlanningRecordingIsCurrent(): void
    {
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures('RecordedProjectPlanning.yaml');

        $user = $this->getFixture('planning_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/project-planning', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty(array_filter($body['projects'], static fn (array $project): bool => null !== $project['loan']), 'The recorded planning holds no projected loan.');
        self::assertNotEmpty(array_filter($body['projects'], static fn (array $project): bool => $project['isDeadlineMissed']), 'The recorded planning holds no missed deadline.');
        self::assertNotEmpty(array_filter($body['months'], static fn (array $month): bool => $month['loanPaymentsCents'] > 0), 'The recorded planning holds no loan payment.');
        self::assertNotEmpty(array_filter($body['projects'], static fn (array $project): bool => null !== $project['investment']), 'The recorded planning holds no investment.');
        self::assertNotEmpty(array_filter($body['months'], static fn (array $month): bool => $month['rentCents'] > 0 && 0 === $month['resaleCents']), 'The recorded planning holds no rent.');
        self::assertNotEmpty(array_filter($body['months'], static fn (array $month): bool => $month['resaleCents'] > 0), 'The recorded planning holds no resale.');

        $this->assertMatchesContract(
            'responses/finance_project_planning.read.json',
            self::stabilise($body),
            'GET /api/finance/project-planning no longer returns what it used to. The mobile DTOs and the admin hook read this response.',
        );
    }

    /**
     * The scenarios and their comparison (MAG-550, read by MAG-551), on the
     * planning world: the three automatic drafts, one of the user's made
     * current, and a comparison against the automatic « Rapide ».
     */
    public function testTheScenarioRecordingsAreCurrent(): void
    {
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures('RecordedProjectPlanning.yaml');

        $user = $this->getFixture('planning_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/finance/scenarios', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode(['name' => 'Sans le Japon', 'projectIds' => [
            (string) $this->getFixture('planning_move')->getId(),
            (string) $this->getFixture('planning_sofa')->getId(),
            (string) $this->getFixture('planning_car')->getId(),
        ], 'monthlySaving' => 60000], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $created = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        $id = $created['scenario']['id'];

        $this->client->request('POST', '/api/finance/scenarios/'.$id.'/make-current', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/finance/scenarios', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $list = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($list);
        self::assertCount(1, array_filter($list['scenarios'], static fn (array $scenario): bool => $scenario['current']), 'The recorded list holds no current scenario.');
        self::assertNotEmpty(array_filter($list['scenarios'], static fn (array $scenario): bool => 'auto' === $scenario['kind']), 'The recorded list holds no automatic scenario.');
        $this->assertMatchesContract(
            'responses/finance_scenarios.collection.json',
            self::stabilise($list),
            'GET /api/finance/scenarios no longer returns what it used to. The mobile DTOs and the admin client read this response.',
        );

        $this->client->request('GET', '/api/finance/scenario-comparison?ids=auto-rapid,'.$id, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $comparison = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($comparison);
        self::assertNotEmpty($comparison['differences'][0]['sentence'], 'The recorded comparison says nothing of the gap.');
        $this->assertMatchesContract(
            'responses/finance_scenario_comparison.read.json',
            self::stabilise($comparison),
            'GET /api/finance/scenario-comparison no longer returns what it used to. The mobile DTOs and the admin client read this response.',
        );

        $this->client->request('POST', '/api/finance/projects/preview', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode(['name' => 'Box à louer', 'amountCents' => 150000, 'wellbeing' => 'high'], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        $preview = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($preview);
        self::assertNotEmpty($preview['dates'], 'The recorded preview lists no project.');
        self::assertNotNull($preview['metrics']['availableAt'], 'The recorded preview never buys the new project.');
        self::assertSame($id, $preview['scenario']['id'], 'The recorded preview is not computed in the current scenario.');
        $this->assertMatchesContract(
            'responses/finance_project_preview.read.json',
            self::stabilise($preview),
            'POST /api/finance/projects/preview no longer returns what it used to. The mobile DTOs and the admin client read this response.',
        );
    }

    /**
     * The detail of a scenario that repays a loan early (MAG-552): the figures
     * of each repayment, read by the mobile and the admin to list it.
     */
    public function testTheScenarioDetailRecordingIsCurrent(): void
    {
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures('RecordedProjectPlanning.yaml', 'RecordedScenarioLoan.yaml');

        $user = $this->getFixture('planning_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/finance/scenarios', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode([
            'name' => 'Je rembourse la voiture',
            'projectIds' => [(string) $this->getFixture('planning_move')->getId()],
            'repayments' => [
                ['loanId' => (string) $this->getFixture('scenario_car_loan')->getId(), 'month' => '2027-02', 'amountCents' => 300000, 'effect' => 'payment'],
            ],
        ], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $created = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);

        $this->client->request('GET', '/api/finance/scenarios/'.$created['scenario']['id'], [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $detail = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($detail);
        self::assertNotEmpty($detail['repaymentEffects'], 'The recorded detail holds no repayment figures.');
        $this->assertMatchesContract(
            'responses/finance_scenario.read.json',
            self::stabilise($detail),
            'GET /api/finance/scenarios/{id} no longer returns what it used to. The mobile DTOs and the admin client read the figures of each repayment from it.',
        );
    }

    /**
     * The 20-year frise (MAG-554), read on 2026-10-21: a current scenario with
     * two projects, a placement and a loan, an automatic scenario beside it
     * and the gap of N between them.
     */
    public function testTheTimelineRecordingIsCurrent(): void
    {
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(\dirname(__DIR__, 2).'/modules/finance/tests/Api/fixtures/timeline.yaml');

        $mine = $this->getFixture('mine');
        self::assertInstanceOf(Scenario::class, $mine);
        $mine->setProjects([
            ['projectId' => (string) $this->getFixture('trip')->getId(), 'position' => 0],
            ['projectId' => (string) $this->getFixture('car')->getId(), 'position' => 1],
        ])->setPlacements([['placementId' => (string) $this->getFixture('pea')->getId()]])->setMonthlySaving(150000);
        $this->em->flush();

        $user = $this->getFixture('test_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/finance/scenarios/'.$mine->getId().'/make-current', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/finance/timeline?compare=auto-quality', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty($body['current']['events'], 'The recorded frise holds no event.');
        self::assertNotNull($body['current']['nDate'], 'The recorded frise never reaches independence.');
        self::assertNotEmpty($body['compared']['events'], 'The recorded comparison holds no event.');
        self::assertNotNull($body['difference'], 'The recorded comparison holds no gap of N.');
        self::assertContains('independence_reached', array_column($body['current']['events'], 'type'));

        $this->assertMatchesContract(
            'responses/finance_timeline.read.json',
            self::stabilise($body),
            'GET /api/finance/timeline no longer returns what it used to. The mobile DTOs (FinanceTimeline.kt) and the admin hook read this response.',
        );
    }

    /**
     * « Peut-être aussi Tom » (MAG-450), as the controller sends it: the
     * labels of unclaimed lines that name the contact.
     */
    public function testTheCounterpartySuggestionsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedToSort.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/contacts/'.$this->getFixture('recorded_contact')->getId().'/counterparty-suggestions', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty($body, 'The recorded world suggests no counterparty, so the recording proves nothing about a suggestion.');

        $this->assertMatchesContract(
            'responses/finance_contact_counterparty_suggestions.read.json',
            self::stabilise($body),
            'GET /api/finance/contacts/{id}/counterparty-suggestions no longer returns what it used to. The mobile DTOs (Contact.kt) are written against this response.',
        );
    }

    /** « Partage » on the transaction sheet (MAG-451): the part kept and the créance, as the controller sends them. */
    public function testTheMyShareRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/transactions/'.$this->getFixture('recorded_one_off_spend')->getId().'/my-share', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotNull($body['peerDebt'] ?? null, 'The recorded spend is not shared, so the recording proves nothing about a créance.');

        $this->assertMatchesContract(
            'responses/finance_transaction_my_share.read.json',
            self::stabilise($body),
            'GET /api/finance/transactions/{id}/my-share no longer returns what it used to. The mobile DTOs (PeerDebt.kt) and the admin read this response.',
        );
    }

    /** « Ce virement est » on a received transfer (MAG-452): the dette declared on it, as the controller sends it. */
    public function testTheTransactionDebtRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedDebt.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/transactions/'.$this->getFixture('recorded_debt_transfer')->getId().'/debt', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotNull($body['peerDebt'] ?? null, 'The recorded transfer is not a dette, so the recording proves nothing about it.');
        self::assertNotNull($body['suggestedContactId'] ?? null, 'The recorded transfer has no contact, so the recording proves nothing about a suggestion.');

        $this->assertMatchesContract(
            'responses/finance_transaction_debt.read.json',
            self::stabilise($body),
            'GET /api/finance/transactions/{id}/debt no longer returns what it used to. The mobile DTOs (PeerDebt.kt) and the admin read this response.',
        );
    }

    /** « Ce virement est » (MAG-453): a received transfer that gives back a créance, as the controller sends it. */
    public function testTheTransactionAllocationRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedAllocation.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/transactions/'.$this->getFixture('recorded_allocation_transfer')->getId().'/allocation', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotSame([], $body['allocations'] ?? [], 'The recorded transfer gives nothing back, so the recording proves nothing about an allocation.');
        self::assertNotNull($body['contact'] ?? null, 'The recorded transfer has no contact.');

        $this->assertMatchesContract(
            'responses/finance_transaction_allocation.read.json',
            self::stabilise($body),
            'GET /api/finance/transactions/{id}/allocation no longer returns what it used to. The mobile DTOs (PeerDebt.kt) and the admin read this response.',
        );
    }

    /**
     * The balance of a month (MAG-457), as the « État courant » dashboard of
     * the phone and the admin reads it.
     */
    public function testTheMonthBalanceRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/month-balance?month=2026-03', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty($body['envelopes'], 'The recorded world budgets no envelope in March, so the recording proves nothing about one.');

        $this->assertMatchesContract(
            'responses/finance_month_balance.read.json',
            self::stabilise($body),
            'GET /api/finance/month-balance no longer returns what it used to. The mobile DTOs (MonthBalance.kt) and the admin hook read this response.',
        );
    }

    /**
     * « Reconnues à contrôler » (MAG-460): the lines an operation recognised in
     * the month, grouped by the operation that recognised them.
     */
    public function testTheRecognisedToControlRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedToSort.yaml');

        $line = $this->getFixture('recorded_to_sort_recognised');
        $line->qualify($line->getQualification(), $this->getFixture('recorded_to_sort_rule'));
        self::getContainer()->get('doctrine.orm.entity_manager')->flush();

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/finance/review/recognised?month=2026-03', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(1, $body['groups'], 'The recorded response must hold one group of one recognised line.');

        $this->assertMatchesContract(
            'responses/finance_review_recognised.read.json',
            self::stabilise($body),
            'GET /api/finance/review/recognised no longer returns what it used to. The mobile DTOs (RecognisedToControl.kt) and the admin hook read this response.',
        );
    }

    /**
     * An account over a month (MAG-422), read at a fixed day so the recording
     * does not move with the clock: March's rent rose by a third that month,
     * the gym's due date of the 20th never came, a transfer names the account
     * opposite and a line is still planned.
     */
    public function testTheAccountTimelineRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml', 'RecordedTimeline.yaml');

        $account = $this->getFixture('recorded_account');
        self::assertInstanceOf(Account::class, $account);

        $timeline = self::getContainer()->get(GetAccountTimeline::class)->execute($account, new \DateTimeImmutable('2026-03-01'), new \DateTimeImmutable('2026-04-22'));
        $body = json_decode((string) json_encode($timeline, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);

        $lines = array_merge(...array_column($body['days'], 'transactions'));
        self::assertNotEmpty(array_filter(array_column($lines, 'counterpartAccount')), 'The recorded timeline holds no transfer, so it proves nothing about `counterpartAccount`.');
        self::assertNotEmpty(array_filter(array_column($lines, 'recurringIncrease')), 'The recorded timeline holds no price rise.');
        self::assertNotEmpty(array_merge(...array_column($body['days'], 'missedOccurrences')), 'The recorded timeline holds no missed due date.');
        self::assertContains('planned', array_column($lines, 'status'));

        $this->assertMatchesContract(
            'responses/finance_account_timeline.read.json',
            self::stabilise($body),
            'GET /api/finance/accounts/{id}/timeline no longer returns what it used to. The mobile DTOs (AccountTimeline.kt) are written against this response.',
        );
    }

    /**
     * What the statement implies, read as the controller sends it: an offer, with no series behind it.
     */
    public function testTheSuggestionsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);

        $suggestions = self::getContainer()->get(SuggestRecurringOperations::class)->suggest($user);
        $body = json_decode((string) json_encode(['suggestions' => $suggestions], \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotEmpty($body['suggestions'], 'The recorded world implies no recurring operation, so the recording proves nothing about a suggestion.');

        $this->assertMatchesContract(
            'responses/recurring_operations.suggestions.json',
            self::stabilise($body),
            'GET /api/finance/recurring-operations/suggestions no longer returns what it used to. The mobile DTOs (RecurringOperation.kt) are written against this response.',
        );
    }

    /**
     * « Tu en prends souvent » (MAG-495), as `/api/grocery-suggestions` sends
     * it: a product bought three times two weeks apart, due on the recorded day.
     */
    public function testTheGrocerySuggestionsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $coffee = (new Product())->setName('Recorded Coffee')->setCategory(ProductCategory::Other)->setUser($user);
        $em->persist($coffee);
        foreach (['2026-03-11', '2026-03-25', '2026-04-08'] as $day) {
            $errand = (new Errand())->setUser($user)->setEndedAt(new \DateTimeImmutable($day.' 10:00:00+02:00'));
            $errand->addItem(PurchasedItem::of('Recorded Coffee', 2, Unit::Pack, $coffee));
            $em->persist($errand);
        }
        $em->flush();
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/grocery-suggestions', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotSame([], $body, 'The recorded world suggests nothing, so the recording proves nothing about a suggestion.');

        $this->assertMatchesContract(
            'responses/grocery_suggestions.json',
            self::stabilise($body),
            'GET /api/grocery-suggestions no longer returns what it used to. The mobile DTOs are written against this response.',
        );
    }

    /**
     * « Pour les recettes » (MAG-590), as `/api/grocery/recipe-needs` sends it:
     * the recorded meal's ingredient, out of stock, over the meal's day.
     */
    public function testTheRecipeNeedsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $ingredient = $this->getFixture('recorded_ingredient');
        self::assertInstanceOf(Product::class, $ingredient);
        $ingredient->setStockState(ProductStockState::Out);
        $em->flush();
        $em->clear();
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/grocery/recipe-needs?from=2026-03-16&to=2026-03-17&missing=true', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotSame([], $body['member'], 'The recorded world misses nothing, so the recording proves nothing about an entry.');

        $this->assertMatchesContract(
            'responses/grocery_recipe_needs.collection.json',
            self::stabilise($body),
            'GET /api/grocery/recipe-needs no longer returns what it used to. The mobile DTOs are written against this response.',
        );
    }

    /**
     * The day (MAG-497), as `/api/day-plans/{date}` sends it once the owner
     * set it: a slot done, « Maintenant », the reserve. Read at a fixed day.
     */
    public function testTheDayPlanRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $path = '/api/day-plans/2026-04-22';
        $slots = array_map(fn (string $ref): array => ['type' => 'task', 'taskId' => (string) $this->getFixture($ref)->getId()], ['recorded_done_task', 'recorded_open_task']);
        $this->client->request('PUT', $path, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode(['slots' => $slots], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotNull($body['now'], 'The recorded day has nothing left to do, so it proves nothing about `now`.');
        self::assertNotSame([], $body['reserve'], 'The recorded day has an empty reserve.');

        $this->assertMatchesContract(
            'responses/day_plans.read.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (DayPlan.kt) are written against this response.', $path),
        );
    }

    /**
     * The evening's review (MAG-500), as `/api/day-reviews/{date}` sends it
     * after « Bonne nuit »: what helped, and when it was done.
     */
    public function testTheDayReviewRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $path = '/api/day-reviews/2026-04-22';
        $this->client->request('PUT', $path, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode(['helped' => ['start_small', 'timer']], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotNull($body['reviewedAt'], 'The recorded review is not done, so it proves nothing about `reviewedAt`.');

        $this->assertMatchesContract(
            'responses/day_reviews.read.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (DayReview.kt) are written against this response.', $path),
        );
    }

    /**
     * The routines (MAG-511), as `/api/routines` sends them once there is
     * a weekly, a monthly and a ticked one: the totals and the last seven days, no streak.
     */
    public function testTheRoutinesRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $headers = array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders());
        $this->client->request('POST', '/api/routines', [], [], $headers, json_encode(['title' => 'Sortir les poubelles', 'anchor' => 'afterDinner', 'recurrence' => ['kind' => 'weekly', 'weekday' => 3]], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $this->client->request('POST', '/api/routines', [], [], $headers, json_encode(['title' => 'Arroser les plantes', 'anchor' => 'wake', 'recurrence' => ['kind' => 'monthly', 'monthDay' => 31]], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $this->client->request('POST', '/api/routines', [], [], $headers, json_encode(['title' => 'Lire', 'anchor' => 'time', 'anchorTime' => '22:30'], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $created = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        $this->client->request('PUT', '/api/routines/'.$created['id'].'/checks/2026-04-22', [], [], $headers);
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/routines', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(3, $body['member']);
        self::assertSame(1, $body['member'][2]['totalCount'], 'The recorded routine is not ticked, so it proves nothing about the totals.');

        $this->assertMatchesContract(
            'responses/routines.collection.json',
            self::stabilise($body),
            'GET /api/routines no longer returns what it used to. The mobile DTOs (Routine.kt) and the admin (routinesApi.ts) are written against this response.',
        );
    }

    /**
     * The trips of a day (MAG-654), as set_trip keeps them: by car, with the
     * car's place, so every optional field is there.
     */
    public function testTheTripsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $event = $em->find(Event::class, $this->getFixture('recorded_event')->getId());
        self::assertInstanceOf(Event::class, $event);
        $em->persist((new Trip($event))->apply(new TripEstimate(
            mode: TripMode::Drive,
            origin: new TripOrigin(TripOrigin::POSITION, null, 'ma position', 38.72, -9.14),
            steps: [new RouteLeg(TripMode::Walk, 240), new RouteLeg(TripMode::Drive, 840), new RouteLeg(TripMode::Walk, 180)],
            durationMinutes: 21,
            departAt: new \DateTimeImmutable('2026-03-15 09:34:00 UTC'),
            navigationUrl: 'https://waze.com/ul?ll=38.7,-9.1&navigate=yes',
            parkingKnown: true,
            carLatitude: 38.7227,
            carLongitude: -9.14,
        ), TripDecider::Maggie, new \DateTimeImmutable('2026-03-15 08:00:00 UTC')));
        $em->flush();

        $this->client->request('GET', '/api/trips?date=2026-03-15', [], [], array_merge(['HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(1, $body['member']);

        $this->assertMatchesContract(
            'responses/trips.collection.json',
            self::stabilise($body),
            'GET /api/trips no longer returns what it used to. The screens of « Partir à l\'heure » are written against this response.',
        );
    }

    /**
     * The routines of a day (MAG-512), as `/api/routines/day/{date}` sends them:
     * grouped by moment, one ticked, one at an hour of its own.
     */
    public function testTheRoutinesDayRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $headers = array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders());
        $this->client->request('POST', '/api/routines', [], [], $headers, json_encode(['title' => 'Sortir les poubelles', 'anchor' => 'afterDinner', 'recurrence' => ['kind' => 'weekly', 'weekday' => 3], 'remind' => true], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $this->client->request('POST', '/api/routines', [], [], $headers, json_encode(['title' => 'Lire', 'anchor' => 'time', 'anchorTime' => '22:30'], \JSON_THROW_ON_ERROR));
        self::assertResponseStatusCodeSame(201);
        $created = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        $this->client->request('PUT', '/api/routines/'.$created['id'].'/checks/2026-04-22', [], [], $headers);
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/routines/day/2026-04-22', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertCount(2, $body['moments']);
        $checked = array_merge(...array_map(static fn (array $moment): array => array_column($moment['routines'], 'checked'), $body['moments']));
        sort($checked);
        self::assertSame([false, true], $checked, 'One routine ticked and one not, or the recording proves nothing about `checked`.');

        $this->assertMatchesContract(
            'responses/routines.day.json',
            self::stabilise($body),
            'GET /api/routines/day/{date} no longer returns what it used to. The mobile DTOs (RoutineDayView.kt) and the admin (routinesApi.ts) are written against this response.',
        );
    }

    /**
     * The bravo of a task done (MAG-508), as `/api/rewards/task/{id}` sends it
     * once the task is ticked: a shooting star, the task having waited a month.
     * The message is drawn by chance among three: the recording keeps the first.
     */
    public function testTheRewardRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
        $taskId = (string) $this->getFixture('recorded_open_task')->getId();

        $this->client->request('PATCH', '/api/tasks/'.$taskId, [], [], array_merge(['CONTENT_TYPE' => 'application/merge-patch+json', 'HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()), json_encode(['completedAt' => '2026-04-22T12:00:00+00:00'], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $path = '/api/rewards/task/'.$taskId;
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertSame('waited', $body['surprise'], 'The recorded reward is no surprise, so it proves nothing about `surprise`.');
        self::assertMatchesRegularExpression('/^waited\.[1-3]$/', $body['messageKey']);
        $body['messageKey'] = 'waited.1';
        $body['message'] = str_replace('{jours}', '33', RewardMessages::SURPRISES['waited'][0]);

        $this->assertMatchesContract(
            'responses/rewards.read.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (Reward.kt) are written against this response.', $path),
        );
    }

    /**
     * A palier, as `/api/rewards/sagaChapter/{id}` sends it (MAG-509): gold, no
     * star, the chapter's title. The message is drawn by chance among three:
     * the recording keeps the first.
     */
    public function testThePalierRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        $chapter = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
        (new RewardGranter(self::getContainer()->get('messenger.bus.default')))->grantPalier($user, 'sagaChapter', $chapter, 'Le chapitre du port');

        $path = '/api/rewards/sagaChapter/'.$chapter;
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertSame('palier', $body['level']);
        self::assertMatchesRegularExpression('/^palier-doux\.[1-3]$/', $body['messageKey']);
        $body['messageKey'] = 'palier-doux.1';
        $body['message'] = RewardMessages::PALIERS['doux'][0];

        $this->assertMatchesContract(
            'responses/rewards.palier.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (Reward.kt) are written against this response.', $path),
        );
    }

    /**
     * The constellation of a month (MAG-509), as `/api/stars` sends it: the
     * task of the reward recording ticked, a month before it holding two stars.
     */
    public function testTheStarsRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
        $taskId = (string) $this->getFixture('recorded_open_task')->getId();

        $this->client->request('PATCH', '/api/tasks/'.$taskId, [], [], array_merge(['CONTENT_TYPE' => 'application/merge-patch+json', 'HTTP_ACCEPT' => 'application/ld+json'], $this->authHeaders()), json_encode(['completedAt' => '2026-04-22T12:00:00+00:00'], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        foreach (['2026-03-03 08:00 UTC', '2026-03-09 08:00 UTC'] as $index => $earnedAt) {
            $em->persist((new Reward())
                ->setUser($user)
                ->setSubjectId('01ARZ3NDEKTSV4RRFFQ69G5FA'.$index)
                ->setMessageKey('doux.1')
                ->setMessage(RewardMessages::TONES['doux'][0])
                ->setEarnedAt(new \DateTimeImmutable($earnedAt)));
        }
        $em->flush();

        $path = '/api/stars?month=2026-04';
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertSame(1, $body['count']);
        self::assertNotNull($body['best'], 'The recorded stars have no best month, so they prove nothing about it.');

        $this->assertMatchesContract(
            'responses/stars.read.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (Stars.kt) are written against this response.', $path),
        );
    }

    /**
     * A button pressed on a task reminder (MAG-510), as
     * `/api/task-reminders/{id}/action` answers « Fait »: the reminder, the
     * reward the tick laid down, the notification now saying it. The reward's
     * message is drawn by chance: the recording keeps the bank's first.
     */
    public function testTheTaskReminderActionRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);
        $taskId = (string) $this->getFixture('recorded_open_task')->getId();

        $em = self::getContainer()->get(EntityManagerInterface::class);
        $user = $em->find(User::class, $user->getId());
        \assert($user instanceof User);
        $now = Clock::get()->now();
        $reminder = (new TaskReminder())->setUser($user)->setKind(TaskReminder::KIND_DUE)->setSubjectId($taskId)
            ->setScheduledFor($now)->setSentAt($now)->setMessageKey('due.overdue.doux.1');
        $notification = (new Notification())->setUser($user)->setType(NotificationType::TaskDue)->setTitle('« Recorded » peut trouver sa place aujourd\'hui, sans pression.')
            ->setRelatedEntityIri('/api/tasks/'.$taskId)->setReminderId((string) $reminder->getId())->setActions(['start', 'done', 'tomorrow']);
        $reminder->setNotification($notification);
        $em->persist($notification);
        $em->persist($reminder);
        $em->flush();

        $path = '/api/task-reminders/'.$reminder->getId().'/action';
        $this->client->request('POST', $path, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode(['action' => 'done'], \JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotNull($body['reward'], 'The recorded answer laid down no reward, so it proves nothing about it.');
        self::assertNotNull($body['notification'], 'The recorded answer took the notification away.');
        $message = $body['reward']['message'];
        self::assertSame($message, $body['notification']['title']);
        $body['reward']['messageKey'] = 'waited.1';
        $body['reward']['message'] = $body['notification']['title'] = str_replace('{jours}', '33', RewardMessages::SURPRISES['waited'][0]);

        $this->assertMatchesContract(
            'responses/task_reminders.action.json',
            self::stabilise($body),
            sprintf('POST %s no longer returns what it used to. The mobile DTOs (TaskReminder.kt) are written against this response.', $path),
        );
    }

    /**
     * The morning's proposal (MAG-498), as `/api/day-plans/{date}/proposal`
     * sends it: slots with their reason, the ranked reserve, the minutes.
     */
    public function testTheDayProposalRecordingIsCurrent(): void
    {
        $this->loadFixtures('RecordedResponses.yaml');

        $user = $this->getFixture('recorded_user');
        self::assertInstanceOf(User::class, $user);
        $this->authenticateAsUser($user);

        // Twenty minutes left of the evening: one task fits, the others wait in the ranked reserve.
        Clock::set(new MockClock('2026-04-22 19:40:00 UTC'));
        $path = '/api/day-plans/2026-04-22/proposal';
        $this->client->request('GET', $path, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $body = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertIsArray($body);
        self::assertNotSame([], $body['slots'], 'The recorded proposal has no slot, so it proves nothing about them.');
        self::assertNotSame([], $body['reserveRanked'], 'The recorded proposal has an empty reserve.');

        $this->assertMatchesContract(
            'responses/day_plans.proposal.json',
            self::stabilise($body),
            sprintf('GET %s no longer returns what it used to. The mobile DTOs (DayProposal.kt) are written against this response.', $path),
        );
    }

    /**
     * Every collection carries at least one member, or the recording proves
     * nothing about the fields.
     */
    public function testEveryRecordedCollectionHasAMember(): void
    {
        foreach (array_keys(self::RECORDINGS) as $name) {
            if (!str_ends_with($name, '.collection')) {
                continue;
            }

            $path = self::contractPath('responses/'.$name.'.json');

            if (!is_file($path)) {
                // testTheRecordingsAreCurrent already reports the absence,
                // with the command that fixes it.
                continue;
            }

            $recorded = json_decode((string) file_get_contents($path), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($recorded);
            self::assertNotEmpty(
                $recorded['member'] ?? [],
                sprintf('The recording "%s" has no member, so the mobile DTO test would deserialise an empty list and pass whatever the DTOs look like.', $name),
            );
        }
    }

    /**
     * Three things in these responses change on every run: the ULIDs, the
     * blank-node IRIs of nested objects, and the audit timestamps the entities
     * stamp in their constructors. Left alone,
     * the recordings would differ every time and the contract would turn into
     * noise — the failure mode of a snapshot that changes too often is that
     * nobody reads its diff.
     *
     * Both are replaced by fixed values of the same shape, so the clients
     * still see a ULID where they expect one and an ISO-8601 instant where
     * they expect one. Every other date — startAt, dueDate, bookedAt,
     * readAt — comes from the fixture and is already deterministic, so it is
     * left exactly as the API emitted it. That matters: a `bookedAt` that
     * switched from a date to a date-time would break the mobile parser, and
     * flattening it here would hide precisely that.
     *
     * @param array<mixed> $value
     *
     * @return array<mixed>
     */
    private static function stabilise(array $value): array
    {
        $stable = [];

        foreach ($value as $key => $item) {
            if (\is_array($item)) {
                $stable[$key] = self::stabilise($item);

                continue;
            }

            if (\is_string($item) && \in_array($key, ['createdAt', 'updatedAt'], true)) {
                $stable[$key] = '2026-01-01T00:00:00+00:00';

                continue;
            }

            if (\is_string($item)) {
                // A bare ULID, or one at the end of an IRI.
                $item = (string) preg_replace('/\b[0-7][0-9A-HJKMNP-TV-Z]{25}\b/', '01ARZ3NDEKTSV4RRFFQ69G5FAV', $item);
                // An object with no identifier of its own gets a random blank-node IRI.
                $item = (string) preg_replace('#/\.well-known/genid/[0-9a-f]+#', '/.well-known/genid/blank', $item);
            }

            $stable[$key] = $item;
        }

        return $stable;
    }
}
