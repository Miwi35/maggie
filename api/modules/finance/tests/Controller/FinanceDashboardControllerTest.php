<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class FinanceDashboardControllerTest extends WebTestCase
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
        $this->client->request('GET', '/api/finance/dashboard');

        self::assertResponseStatusCodeSame(401);
    }

    public function testOutOfRangeMonthReturns400(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=13', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testOutOfRangeYearReturns400(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=1999&month=9', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testReturnsTheScoresAndTheThreeTilesOfTheHome(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('2026-09-30', $data['scores']['date']);
        self::assertSame(['week', 'month', 'global'], array_values(array_diff(array_keys($data['scores']), ['date'])));

        self::assertSame(950000, $data['tiles']['accounts']['totalCents']);
        self::assertSame(750000, $data['tiles']['accounts']['cushionCents']);
        self::assertCount(2, $data['tiles']['accounts']['accounts']);

        self::assertCount(2, $data['tiles']['budget']['envelopes']);
        self::assertSame(250000, $data['tiles']['budget']['expectedSalary']['monthlyCents']);
        self::assertSame('declared', $data['tiles']['budget']['expectedSalary']['source']);

        self::assertArrayHasKey('independence', $data['tiles']['planning']);
        self::assertArrayHasKey('netCapacityCents', $data['tiles']['planning']['savingCapacity']);
    }

    public function testReturnsWhatIsToDoForTheTilesPills(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(
            ['toSortCount', 'rejectionsToSettleCount', 'reviewReady', 'advanceToRepayCents'],
            array_keys($data['todo']),
        );
        self::assertIsInt($data['todo']['toSortCount']);
        self::assertSame(0, $data['todo']['rejectionsToSettleCount']);
        self::assertIsBool($data['todo']['reviewReady']);
        self::assertSame(0, $data['todo']['advanceToRepayCents']);
    }

    public function testReturnsTheWholePictureInOneCall(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('green', $data['score']['score']);
        self::assertSame(950000, $data['balance']['totalCents']);
        self::assertCount(12, $data['monthlyFlows']);
        self::assertCount(2, $data['budgets']);
        self::assertSame('Alimentation', $data['topPosts'][0]['categoryName']);
        self::assertArrayHasKey('netCapacityCents', $data['savingCapacity']);
        self::assertSame(['monthlyCents' => 250000, 'source' => 'declared'], $data['score']['referenceSalary']);
        self::assertSame(250000, $data['savingCapacity']['monthlyNetIncomeCents']);
    }

    public function testTopPostsCarryTheCategoryIdTheRestOfTheApiUses(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $byName = array_column($data['topPosts'], 'categoryId', 'categoryName');

        self::assertSame((string) $this->getFixture('food')->getId(), $byName['Alimentation']);
        self::assertSame((string) $this->getFixture('leisure')->getId(), $byName['Loisirs']);
        self::assertSame(26, \strlen($byName['Alimentation']), 'a ULID in base32, not RFC 4122');

        $this->client->request('GET', '/api/categories/'.$byName['Alimentation'], [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
    }

    public function testThePostWithoutACategoryKeepsANullCategoryId(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard?year=2026&month=9', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $uncategorized = array_values(array_filter($data['topPosts'], static fn (array $post) => null === $post['categoryName']));

        self::assertCount(1, $uncategorized);
        self::assertNull($uncategorized[0]['categoryId']);
        self::assertSame(1500, $uncategorized[0]['spentCents']);
    }

    public function testDefaultsToTheCurrentPeriod(): void
    {
        $this->loadFixtures('dashboard.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();

        $now = new \DateTimeImmutable();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame((int) $now->format('Y'), $data['year']);
        self::assertSame((int) $now->format('n'), $data['month']);
    }
}
