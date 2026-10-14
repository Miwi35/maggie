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
        self::assertCount(1, $data['budgets']);

        $byCategory = array_column($data['budgets'], null, 'categoryName');

        // Only the July debit counts against the monthly food envelope.
        self::assertSame(40000, $byCategory['Alimentation']['amountCents']);
        self::assertSame(4599, $byCategory['Alimentation']['spentCents']);
        self::assertSame(35401, $byCategory['Alimentation']['remainingCents']);
        self::assertFalse($byCategory['Alimentation']['isOverspent']);
        // No threshold set: the one a sync alerts at is the default (MAG-49).
        self::assertSame(80, $byCategory['Alimentation']['alertThresholdPercent']);

        // The annual leisure envelope is kept apart from the month's budget...
        self::assertArrayNotHasKey('Loisirs', $byCategory);
        self::assertSame(40000, $data['totalBudgetedCents']);
        self::assertSame(4599, $data['totalSpentCents']);
        self::assertSame(35401, $data['totalRemainingCents']);

        // ...and spans the year, so the March debit counts in it.
        $annual = $data['outsideMonthlyBudget']['annual'];
        self::assertCount(1, $annual);
        self::assertSame('annual', $annual[0]['mode']);
        self::assertSame('Loisirs', $annual[0]['categoryName']);
        self::assertSame(1200, $annual[0]['spentCents']);
        self::assertSame(118800, $annual[0]['remainingCents']);
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

    public function testASpendAttributedToAOneOffEnvelopeLeavesItsCategorysEnvelope(): void
    {
        $this->loadFixtures('one_off_envelope.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // Hellfest 2026 = 400 €; the 89 € ticket is classed Loisirs, then attributed to Hellfest.
        $this->client->request('PATCH', '/api/transactions/'.$this->getFixture('ticket')->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['oneOffEnvelope' => '/api/envelopes/'.$this->getFixture('hellfest')->getId()], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $data = $this->budgetStatus(2026, 10);

        // Loisirs keeps only the 25 € bar: neither the ticket nor the tent attributed to Hellfest.
        $leisure = array_column($data['budgets'], null, 'categoryName')['Loisirs'];
        self::assertSame(2500, $leisure['consumedCents']);
        self::assertSame(0, $leisure['plannedCents']);

        $hellfest = array_column($data['outsideMonthlyBudget']['oneOff'], null, 'name')['Hellfest 2026'];
        self::assertSame('one_off', $hellfest['mode']);
        self::assertSame(40000, $hellfest['amountCents']);
        self::assertSame(8900, $hellfest['consumedCents']);
        self::assertSame(3000, $hellfest['plannedCents']);
        self::assertSame(31100, $hellfest['remainingCents']);
        self::assertSame(28100, $hellfest['availableCents']);
        self::assertFalse($hellfest['isOverspent']);

        // The other user's one-off envelope is not listed.
        self::assertCount(1, $data['outsideMonthlyBudget']['oneOff']);

        // Taking the attribution back puts the ticket back in Loisirs.
        $this->client->request('PATCH', '/api/transactions/'.$this->getFixture('ticket')->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['oneOffEnvelope' => null], JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();

        $data = $this->budgetStatus(2026, 10);
        self::assertSame(11400, array_column($data['budgets'], null, 'categoryName')['Loisirs']['consumedCents']);
        self::assertSame(0, array_column($data['outsideMonthlyBudget']['oneOff'], null, 'name')['Hellfest 2026']['consumedCents']);
    }

    public function testAnAnnualEnvelopeStaysOutOfTheMonthsTotals(): void
    {
        $this->loadFixtures('one_off_envelope.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->budgetStatus(2026, 10);

        // Loisirs 300 € is the month's whole budget: Voyages 1 200 € is annual.
        self::assertSame(30000, $data['totalBudgetedCents']);
        self::assertSame(['Loisirs'], array_column($data['budgets'], 'categoryName'));

        $travel = $data['outsideMonthlyBudget']['annual'][0];
        self::assertSame('Voyages', $travel['categoryName']);
        self::assertSame(120000, $travel['amountCents']);
        self::assertSame(6000, $travel['consumedCents']);
        self::assertNull($travel['parentEnvelopeId']);
    }

    /** @return array<string, mixed> */
    private function budgetStatus(int $year, int $month): array
    {
        $this->client->request('GET', sprintf('/api/finance/budget-status?year=%d&month=%d', $year, $month), [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
