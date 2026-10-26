<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Tests\UseCase\RecurringProjectionsWorld;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The treasury outlook over REST (MAG-279): a pure read whose one parameter,
 * the horizon in months (MAG-506), is refused out of 1 to 12; the overdraft
 * limit is refused by the treasury settings (TreasurySettingsControllerTest).
 * Exemption: N/A — DB state, Mercure and Elasticsearch, it writes nothing.
 */
class TreasuryOutlookControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use RecurringProjectionsWorld;

    private const string ENDPOINT = '/api/finance/treasury-outlook';

    private KernelBrowser $client;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testItGivesTheDelayTheOverdraftAndTheAdvice(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/treasury_outlook.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['salary']['isConfigured']);
        self::assertTrue($data['salary']['isLate']);
        // Nothing is credited: the previous month is open, and the current one too once the 25th has passed.
        $openMonths = (int) (new \DateTimeImmutable('today'))->format('j') > 25 ? 2 : 1;
        self::assertSame(495000 * $openMonths, $data['salary']['shortfallCents']);
        self::assertSame(-12000, $data['balanceCents']);
        self::assertNotEmpty($data['projection']);
        self::assertTrue($data['overdraft']['isForecast']);
        self::assertSame(12000, $data['advice']['amountCents']);
        self::assertSame((string) $this->getFixture('livret')->getId(), $data['advice']['sourceAccountId']);
        self::assertSame(0, $data['outstandingAdvancesCents']);
    }

    public function testTheOverdraftLimitSetInTheSettingsIsHonoured(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/treasury_outlook.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('PATCH', '/api/finance/treasury-settings', [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], '{"overdraftLimitCents": 30000}');
        self::assertResponseIsSuccessful();
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(30000, $data['overdraft']['limitCents']);
        self::assertFalse($data['overdraft']['isForecast']);
        self::assertNull($data['advice']);
    }

    /** MAG-506: the series' charges weigh on the projection past the salary, to the end of the next month at least. */
    public function testTheRecurringChargesWeighOnTheProjectionToTheEndOfTheNextMonth(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/treasury_outlook.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $today = new \DateTimeImmutable('today');
        $this->series('Abonnement', -2500, $today->format('Y-m-d'), categoryRef: 'leisure', period: RecurrencePeriod::Weekly);

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $last = $data['projection'][array_key_last($data['projection'])];
        self::assertGreaterThanOrEqual($today->modify('last day of next month')->format('Y-m-d'), $last['date']);

        $weekly = array_filter(
            array_merge(...array_column($data['projection'], 'movements')),
            static fn (array $movement): bool => 'recurring' === $movement['source'],
        );
        self::assertGreaterThanOrEqual(5, \count($weekly));
        self::assertSame(-12000 - 2500 * \count($weekly), $last['balanceCents']);
    }

    public function testTheHorizonAskedForSetsHowFarTheProjectionRuns(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/treasury_outlook.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $today = new \DateTimeImmutable('today');

        $this->client->request('GET', self::ENDPOINT, ['months' => 12], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $last = $data['projection'][array_key_last($data['projection'])];
        self::assertSame($today->modify('+12 months')->modify('-1 day')->format('Y-m-d'), $last['date']);
        self::assertArrayHasKey('cumulativeInflowCents', $last);
        self::assertArrayHasKey('cumulativeOutflowCents', $last);
    }

    /** @return iterable<string, array{string}> */
    public static function badHorizons(): iterable
    {
        yield 'zero' => ['0'];
        yield 'over a year' => ['13'];
        yield 'not a number' => ['abc'];
        yield 'empty' => [''];
    }

    #[DataProvider('badHorizons')]
    public function testAHorizonOutOfRangeIsRefused(string $months): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/treasury_outlook.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, ['months' => $months], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }
}
