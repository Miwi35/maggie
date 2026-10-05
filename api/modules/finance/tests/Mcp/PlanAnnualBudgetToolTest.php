<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\PlanAnnualBudgetTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class PlanAnnualBudgetToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    /** The world is shared with the controller suite: two copies would drift. */
    private const FIXTURE = __DIR__.'/../Controller/fixtures/annual_plan.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testReviewReportsTheYearToPrepare(): void
    {
        $this->signIn();

        $plan = $this->call('review', year: 2027);

        self::assertSame(2027, $plan['year']);
        self::assertSame(2026, $plan['sourceYear']);
        self::assertSame(143000, $plan['totalSuggestedCents']);
        self::assertSame(['Voyages', 'Courses', 'Loisirs'], array_column($plan['categories'], 'categoryName'));
    }

    public function testACallNobodyIsBoundToIsRefused(): void
    {
        $this->loadFixtures(self::FIXTURE);

        // Resolved before the action is read, so an unbound call says what is
        // actually wrong instead of arguing about the action.
        $result = $this->call('review', year: 2027);

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
        self::assertArrayNotHasKey('categories', $result);
    }

    public function testAYearNoSessionCouldBeAboutIsRefused(): void
    {
        $this->signIn();

        // The HTTP side has always checked the range; nothing validates an
        // Envelope on the bus, so unchecked this wrote a year the admin
        // cannot show.
        $result = $this->call(
            'budget',
            year: 20330,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            amountCents: 1000,
        );

        self::assertStringContainsString('year must be between 2000 and 2100', $result['error']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(4, $em->getRepository(Envelope::class)->findAll());
    }

    public function testSchedulePlansOneExpense(): void
    {
        $this->signIn();

        $result = $this->call(
            'schedule',
            year: 2027,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            label: 'Festival 2027',
            amountCents: 26000,
            month: 7,
            accountId: (string) $this->getFixture('checking')->getId(),
        );

        self::assertTrue($result['success']);
        self::assertSame(-26000, $result['event']['amountCents']);
        self::assertSame('2027-07-01', $result['event']['bookedAt']);
        self::assertSame('planned', $result['event']['status']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $transaction = $em->getRepository(Transaction::class)->findOneBy(['label' => 'Festival 2027']);
        self::assertNotNull($transaction);
        self::assertSame(-26000, $transaction->getAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testScheduleTakesTheStatusItIsGiven(): void
    {
        $this->signIn();

        $result = $this->call(
            'schedule',
            year: 2027,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            label: 'Billet pris',
            amountCents: 8000,
            month: 3,
            status: 'committed',
            accountId: (string) $this->getFixture('checking')->getId(),
        );

        self::assertSame('committed', $result['event']['status']);
    }

    public function testBudgetWritesTheDecidedAmount(): void
    {
        $this->signIn();

        $result = $this->call(
            'budget',
            year: 2027,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            amountCents: 50000,
        );

        self::assertTrue($result['success']);
        self::assertSame(50000, $result['envelope']['amountCents']);
        self::assertSame('annual', $result['envelope']['mode']);
        self::assertSame('created', $result['envelope']['outcome']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertCount(5, $em->getRepository(Envelope::class)->findAll());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testBudgetOverwritesWhatIsAlreadyThere(): void
    {
        $this->signIn();
        $existing = $this->getFixture('travel_2027_annual');

        $result = $this->call(
            'budget',
            year: 2027,
            categoryId: (string) $this->getFixture('travel')->getId(),
            amountCents: 120000,
        );

        self::assertSame((string) $existing->getId(), $result['envelope']['id']);
        self::assertSame('updated', $result['envelope']['outcome']);
    }

    public function testUnknownActionIsReported(): void
    {
        $this->signIn();

        self::assertStringContainsString('Unknown action', $this->call('forecast')['error']);
    }

    public function testScheduleWithoutACategoryIsReported(): void
    {
        $this->signIn();

        $result = $this->call('schedule', year: 2027, label: 'Sans catégorie', amountCents: 1000);

        self::assertStringContainsString('categoryId', $result['error']);
    }

    public function testSpentIsRefused(): void
    {
        $this->signIn();

        $result = $this->call(
            'schedule',
            year: 2027,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            label: 'Déjà payé',
            amountCents: 1000,
            status: 'spent',
            accountId: (string) $this->getFixture('checking')->getId(),
        );

        self::assertStringContainsString('status', $result['error']);
    }

    public function testBudgetWithoutAnAmountIsReported(): void
    {
        $this->signIn();

        $result = $this->call('budget', year: 2027, categoryId: (string) $this->getFixture('leisure')->getId());

        self::assertStringContainsString('amountCents', $result['error']);
    }

    public function testAnotherUsersAccountIsRefused(): void
    {
        $this->signIn();

        $result = $this->call(
            'schedule',
            year: 2027,
            categoryId: (string) $this->getFixture('leisure')->getId(),
            label: 'Chez le voisin',
            amountCents: 1000,
            accountId: (string) $this->getFixture('other_checking')->getId(),
        );

        self::assertStringContainsString('account not found', $result['error']);
    }

    public function testAnotherUsersCategoryIsRefused(): void
    {
        $this->signIn();

        $result = $this->call(
            'budget',
            year: 2027,
            categoryId: (string) $this->getFixture('other_leisure')->getId(),
            amountCents: 1000,
        );

        self::assertStringContainsString('category not found', $result['error']);
    }

    public function testReviewOnlySeesItsOwnUser(): void
    {
        $this->loadFixtures(self::FIXTURE);
        $this->loginFixtureUser('other_user');

        $plan = $this->call('review', year: 2027);

        self::assertSame(['Loisirs'], array_column($plan['categories'], 'categoryName'));
        self::assertSame(99000, $plan['totalLastYearConsumedCents']);
    }

    private function signIn(): void
    {
        $this->loadFixtures(self::FIXTURE);
        $this->loginFixtureUser();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        /** @var PlanAnnualBudgetTool $tool */
        $tool = self::getContainer()->get(PlanAnnualBudgetTool::class);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }
}
