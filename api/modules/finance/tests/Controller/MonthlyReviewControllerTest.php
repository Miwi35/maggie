<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class MonthlyReviewControllerTest extends WebTestCase
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
        $this->client->request('GET', '/api/finance/monthly-review');

        self::assertResponseStatusCodeSame(401);
    }

    public function testOutOfRangeMonthReturns400(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/monthly-review?year=2026&month=13', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReturnsWhatIsLeftToJudgeAndTheComparisons(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/monthly-review?year=2026&month=8', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(3, $data['pendingCount']);
        self::assertNull($data['optimisationScore']);
        self::assertSame(110000, $data['comparison']['thisMonthCents']);
        self::assertSame(60000, $data['comparison']['previousMonthCents']);
    }

    public function testDefaultsToTheMonthJustEnded(): void
    {
        $this->loadFixtures('monthly_review.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/monthly-review', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $lastMonth = new \DateTimeImmutable('first day of last month');
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame((int) $lastMonth->format('Y'), $data['year']);
        self::assertSame((int) $lastMonth->format('n'), $data['month']);
    }
}
