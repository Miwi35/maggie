<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Tests\UseCase\FinanceScoresWorld;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** A pure read: no Mercure update is published and nothing is indexed. */
class FinanceScoresControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use FinanceScoresWorld;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/scores');

        self::assertResponseStatusCodeSame(401);
    }

    #[DataProvider('badDates')]
    public function testAnInvalidDateReturns400(string $date): void
    {
        $this->loadFixtures($this->financeScoresFixture());
        $this->authenticateAsUser($this->getFixture('scores_user'));

        $this->client->request('GET', '/api/finance/scores?date='.urlencode($date), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    /** @return iterable<string, array{string}> */
    public static function badDates(): iterable
    {
        yield 'not a date' => ['tomorrow'];
        yield 'month 13' => ['2026-13-01'];
        yield 'day that does not exist' => ['2026-02-30'];
        yield 'wrong order' => ['13-10-2026'];
        yield 'out of range' => ['1999-12-31'];
    }

    public function testReturnsTheWeekTheMonthAndTheGlobalScore(): void
    {
        $this->loadFixtures($this->financeScoresFixture());
        $this->authenticateAsUser($this->getFixture('scores_user'));
        $this->spendGroceries('2026-10-03', 42000);

        $this->client->request('GET', '/api/finance/scores?date=2026-10-13', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('2026-10-13', $data['date']);
        self::assertSame('orange', $data['month']['status']);
        self::assertSame('Attention', $data['month']['label']);
        self::assertSame(108194, $data['month']['rhythm']['expectedSpentCents']);
        self::assertSame(120000, $data['month']['rhythm']['spentCents']);
        self::assertSame('above', $data['month']['rhythm']['status']);
        self::assertCount(31, $data['month']['rhythm']['curve']);
        self::assertCount(2, $data['month']['rhythm']['debits']);
        self::assertSame('orange', $data['global']['status']);
        self::assertArrayNotHasKey('rhythm', $data['global']);
        self::assertCount(7, $data['week']['rhythm']['curve']);
    }

    public function testTheDailyScoreEndpointIsGone(): void
    {
        $this->loadFixtures($this->financeScoresFixture());
        $this->authenticateAsUser($this->getFixture('scores_user'));

        $this->client->request('GET', '/api/finance/daily-score', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testDefaultsToToday(): void
    {
        $this->loadFixtures($this->financeScoresFixture());
        $this->authenticateAsUser($this->getFixture('scores_user'));

        $this->client->request('GET', '/api/finance/scores', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $today = (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        self::assertSame($today, $data['date']);
    }
}
