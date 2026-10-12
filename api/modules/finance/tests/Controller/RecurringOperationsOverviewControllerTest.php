<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** What is read of the recurring operations, over REST (MAG-306): a pure read, no body, no parameter. */
class RecurringOperationsOverviewControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const string ENDPOINT = '/api/finance/recurring-operations/overview';
    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/recurring_overview_live.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    /** @return array<string, mixed> */
    private function overview(): array
    {
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testItReadsTheCostAndTheNextDueDate(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->authenticateAsUser($this->getFixture('test_user'));

        $data = $this->overview();

        self::assertResponseIsSuccessful();
        self::assertCount(1, $data['operations']);
        $flixo = $data['operations'][0];
        self::assertSame((string) $this->getFixture('flixo')->getId(), $flixo['id']);
        self::assertSame('Abonnement Flixo', $flixo['label']);
        self::assertSame('Abonnements', $flixo['categoryName']);
        self::assertSame(-1349, $flixo['monthlyCostCents']);
        self::assertSame(-16188, $flixo['yearlyCostCents']);
        self::assertSame(1, $flixo['attachmentCount']);
        self::assertSame((new \DateTimeImmutable('first day of next month'))->format('Y-m-d'), $flixo['nextOccurrenceOn']);
        self::assertNull($flixo['missedOccurrence']);
        self::assertNull($flixo['priceChange']);
        self::assertSame([], $data['proposals']);
        self::assertSame(
            ['monthlyCostCents' => -1349, 'yearlyCostCents' => -16188, 'monthlyIncomeCents' => 0, 'activeCount' => 1],
            $data['totals'],
        );
    }

    public function testAUserNeverReadsTheSeriesOfAnother(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->authenticateAsUser($this->getFixture('other_user'));

        $data = $this->overview();

        self::assertSame(['Abonnement secret'], array_column($data['operations'], 'label'));
        self::assertSame(-999, $data['totals']['monthlyCostCents']);
        self::assertStringNotContainsString('Abonnement Flixo', (string) $this->client->getResponse()->getContent());
    }
}
