<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class BudgetStatusControllerTest extends WebTestCase
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

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/budget-status?year=2026&month=7');

        self::assertResponseStatusCodeSame(401);
    }

    public function testOutOfRangeMonthReturns400(): void
    {
        $this->loadFixtures('budget_status.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/budget-status?year=2026&month=13', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testOutOfRangeYearReturns400(): void
    {
        $this->loadFixtures('budget_status.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/budget-status?year=1789&month=7', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReturnsSpentAndRemainingPerEnvelope(): void
    {
        $this->loadFixtures('budget_status.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/budget-status?year=2026&month=7', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(2026, $data['year']);
        self::assertSame(7, $data['month']);
        self::assertCount(2, $data['budgets']);

        $byCategory = array_column($data['budgets'], null, 'categoryName');

        // Only the July debit counts against the monthly food envelope.
        self::assertSame(40000, $byCategory['Alimentation']['amountCents']);
        self::assertSame(4599, $byCategory['Alimentation']['spentCents']);
        self::assertSame(35401, $byCategory['Alimentation']['remainingCents']);
        self::assertFalse($byCategory['Alimentation']['isOverspent']);

        // The annual leisure envelope spans the year, so the March debit counts.
        self::assertSame('annual', $byCategory['Loisirs']['mode']);
        self::assertSame(1200, $byCategory['Loisirs']['spentCents']);

        self::assertSame(160000, $data['totalBudgetedCents']);
        self::assertSame(5799, $data['totalSpentCents']);
        self::assertSame(154201, $data['totalRemainingCents']);
    }

    public function testDefaultsToTheCurrentPeriod(): void
    {
        $this->loadFixtures('budget_status.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/budget-status', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $now = new \DateTimeImmutable();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame((int) $now->format('Y'), $data['year']);
        self::assertSame((int) $now->format('n'), $data['month']);
    }

    public function testASpendCountsInTheEnvelopeOfItsCategoryAndOfEveryMother(): void
    {
        $this->loadFixtures('budget_status_nested.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/budget-status?year=2026&month=10', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        // Bars and Alimentation have no envelope: they do not show up as one.
        self::assertCount(2, $data['budgets']);
        $byCategory = array_column($data['budgets'], null, 'categoryName');

        // 40 € at Obsydian: Obsydian 40 / 100, the 5 € to arbitrate weigh on nothing.
        $obsydian = $byCategory['Obsydian'];
        self::assertSame(10000, $obsydian['amountCents']);
        self::assertSame(4000, $obsydian['consumedCents']);
        self::assertSame(6000, $obsydian['remainingCents']);
        self::assertSame(500, $obsydian['toArbitrateCents']);
        self::assertSame('Loisirs › Bars › Obsydian', $obsydian['categoryPath']);
        self::assertSame((string) $this->getFixture('leisure_october')->getId(), $obsydian['parentEnvelopeId']);

        // Loisirs holds Obsydian's 40 € and the 25 € spent on Bars: 65 / 300.
        $leisure = $byCategory['Loisirs'];
        self::assertSame(30000, $leisure['amountCents']);
        self::assertSame(6500, $leisure['spentCents']);
        self::assertSame(6500, $leisure['consumedCents']);
        self::assertSame(23500, $leisure['remainingCents']);
        self::assertSame(1000, $leisure['plannedCents']);
        self::assertSame(500, $leisure['toArbitrateCents']);
        self::assertSame(22500, $leisure['availableCents']);
        self::assertSame('Loisirs', $leisure['categoryPath']);
        self::assertNull($leisure['parentEnvelopeId']);
        self::assertFalse($leisure['isOverspent']);

        // The total budgeted is Loisirs' 300 €, not 400 €, and nothing is counted twice.
        self::assertSame(30000, $data['totalBudgetedCents']);
        self::assertSame(6500, $data['totalSpentCents']);
        self::assertSame(6500, $data['totalConsumedCents']);
        self::assertSame(1000, $data['totalPlannedCents']);
        self::assertSame(23500, $data['totalRemainingCents']);
        self::assertSame(22500, $data['totalAvailableCents']);
    }

    public function testAnEnvelopeWithNoEnvelopeAboveItIsARoot(): void
    {
        $this->loadFixtures('budget_status_nested.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->remove($this->getFixture('leisure_october'));
        $em->flush();

        $this->client->request('GET', '/api/finance/budget-status?year=2026&month=10', [], [], $this->authHeaders());

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(1, $data['budgets']);
        self::assertNull($data['budgets'][0]['parentEnvelopeId']);
        self::assertSame(10000, $data['totalBudgetedCents']);
        self::assertSame(4000, $data['totalSpentCents']);
    }
}
