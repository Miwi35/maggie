<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The treasury outlook over REST (MAG-279): a pure read with no parameter, so
 * no 400 here — the one input, the overdraft limit, is refused by the
 * treasury settings (TreasurySettingsControllerTest). Exemption: N/A — DB
 * state, Mercure and Elasticsearch, it writes nothing.
 */
class TreasuryOutlookControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const string ENDPOINT = '/api/finance/treasury-outlook';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
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
        self::assertSame(495000, $data['salary']['shortfallCents']);
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
}
