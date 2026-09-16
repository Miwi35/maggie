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
}
