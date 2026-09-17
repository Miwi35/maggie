<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class DailyScoreControllerTest extends WebTestCase
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
        $this->client->request('GET', '/api/finance/daily-score');

        self::assertResponseStatusCodeSame(401);
    }

    public function testOutOfRangeMonthReturns400(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/daily-score?year=2026&month=0', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReturnsTheScoreWithItsReasons(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/daily-score?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('green', $data['score']);
        self::assertSame(60000, $data['budget']['totalBudgetedCents']);
        self::assertSame(25000, $data['budget']['totalConsumedCents']);
        self::assertSame('complete', $data['cushion']['state']);
        self::assertContains('below_last_year', array_column($data['reasons'], 'code'));
    }

    public function testDefaultsToTheCurrentPeriod(): void
    {
        $this->loadFixtures('daily_score.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/daily-score', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $now = new \DateTimeImmutable();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame((int) $now->format('Y'), $data['year']);
        self::assertSame((int) $now->format('n'), $data['month']);
    }
}
