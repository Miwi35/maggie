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

    public function testRolloverFromOctoberToNovemberBudgetsTheWinterSeason(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/seasonal_envelope.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover(['fromYear' => 2026, 'fromMonth' => 10, 'year' => 2026, 'month' => 11]);

        self::assertResponseIsSuccessful();
        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(4500, $data['envelopes'][0]['monthAmountCents']);

        $november = $this->cinema(2026, 11);
        self::assertSame(4500, $november->getMonthAmountCents());
        self::assertSame(2000, $november->getAmountCents());

        $this->assertMercureUpdatePublished('/envelopes/');
        $this->assertElasticsearchIndexDispatched(Envelope::class);
    }

    public function testRolloverFromFebruaryToMarchLeavesTheSeason(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/seasonal_envelope.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->rollover(['fromYear' => 2027, 'fromMonth' => 2, 'year' => 2027, 'month' => 3]);

        self::assertResponseIsSuccessful();
        self::assertSame(2000, $this->cinema(2027, 3)->getMonthAmountCents());
    }

    public function testRolloverOnActualSpendingInASeasonReplacesTheSeasonAmount(): void
    {
        $this->loadFixtures(__DIR__.'/../Api/fixtures/seasonal_envelope.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        // February consumed 10 €: that measures the winter season, not the 20 € base.
        $this->rollover(['fromYear' => 2027, 'fromMonth' => 2, 'year' => 2027, 'month' => 3, 'useActualSpending' => true]);

        self::assertResponseIsSuccessful();
        $march = $this->cinema(2027, 3);
        self::assertSame(2000, $march->getAmountCents());
        self::assertSame([['fromMonth' => 11, 'toMonth' => 2, 'amountCents' => 1000]], $march->getSeasonalAmounts());
    }

    private function cinema(int $year, int $month): Envelope
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $envelope = $em->getRepository(Envelope::class)->findOneBy([
            'category' => $this->getFixture('cinema')->getId(),
            'year' => $year,
            'month' => $month,
        ]);
        self::assertNotNull($envelope);

        return $envelope;
    }
}
