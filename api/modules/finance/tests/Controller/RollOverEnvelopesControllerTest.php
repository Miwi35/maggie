<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Envelope;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class RollOverEnvelopesControllerTest extends WebTestCase
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

    /** @param array<string, mixed> $body */
    private function rollover(array $body): void
    {
        $this->client->request('POST', '/api/finance/rollover-envelopes', [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/rollover-envelopes', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['fromYear' => 2026, 'year' => 2026], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testMissingYearsReturns400(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover(['fromMonth' => 9]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testOutOfRangeMonthReturns400(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover(['fromYear' => 2026, 'fromMonth' => 9, 'year' => 2026, 'month' => 13]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testRolloverCreatesTheNextMonthEnvelopes(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover(['fromYear' => 2026, 'fromMonth' => 9, 'year' => 2026, 'month' => 10]);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(1, $data['created']);
        self::assertSame(40000, $data['envelopes'][0]['amountCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Envelope::class)->findAll());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testRolloverOnActualSpendingUsesWhatWentOut(): void
    {
        $this->loadFixtures('envelope_statuses.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover([
            'fromYear' => 2026,
            'fromMonth' => 9,
            'year' => 2026,
            'month' => 10,
            'useActualSpending' => true,
        ]);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(25000, $data['envelopes'][0]['amountCents']);
    }
}
