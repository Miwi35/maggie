<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class DetectInternalTransfersControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/finance/internal-transfers/detect';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function detect(array $body = []): array
    {
        $this->client->request(
            'POST',
            self::ENDPOINT,
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            [] === $body ? '' : json_encode($body, JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function tx(string $ref): Transaction
    {
        /** @var Transaction $transaction */
        $transaction = $this->getFixture($ref);

        return $transaction;
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testANonIntegerLimitDaysReturns400(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->detect(['limitDays' => 'trois mois']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testANegativeLimitDaysReturns400(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->detect(['limitDays' => -30]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testADryRunThatIsNotABooleanReturns400(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $out = $this->tx('transfer_out');

        $this->detect(['dryRun' => 'false']);

        self::assertResponseStatusCodeSame(400, '"false" is truthy, and a dry run that writes is the whole risk');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(TransferKind::None, $em->find(Transaction::class, $out->getId())->getTransferKind());
    }

    public function testTheCatchUpPairsBothLegsAndTellsEveryScreen(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');
        $groceries = $this->tx('groceries');

        $data = $this->detect();

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertSame(1, $data['matched']);
        self::assertFalse($data['dryRun']);
        self::assertSame((string) $out->getId(), $data['pairs'][0]['transactionId']);
        self::assertSame((string) $in->getId(), $data['pairs'][0]['counterpartId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $storedOut = $em->find(Transaction::class, $out->getId());
        $storedIn = $em->find(Transaction::class, $in->getId());
        $storedGroceries = $em->find(Transaction::class, $groceries->getId());

        self::assertSame(TransferKind::Internal, $storedOut->getTransferKind());
        self::assertSame(TransferKind::Internal, $storedIn->getTransferKind());
        self::assertSame(TransferSource::Auto, $storedOut->getTransferSource());
        self::assertSame((string) $in->getId(), (string) $storedOut->getCounterpart()?->getId());
        self::assertSame((string) $out->getId(), (string) $storedIn->getCounterpart()?->getId());
        self::assertSame(TransferKind::None, $storedGroceries->getTransferKind());

        $this->assertMercureUpdatePublished((string) $out->getId());
        $this->assertMercureUpdatePublished((string) $in->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $out->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $in->getId());
    }

    public function testADryRunReportsThePairAndWritesNothing(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        $data = $this->detect(['dryRun' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['dryRun']);
        self::assertSame(1, $data['matched']);
        self::assertSame(-300000, $data['pairs'][0]['amountCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        self::assertSame(TransferKind::None, $em->find(Transaction::class, $out->getId())->getTransferKind());
        self::assertSame(TransferKind::None, $em->find(Transaction::class, $in->getId())->getTransferKind());
        self::assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testTheAccountBalancesDoNotMove(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/finance/dashboard', [], [], $this->authHeaders());
        $before = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['balance'];

        $this->detect();

        $this->client->request('GET', '/api/finance/dashboard', [], [], $this->authHeaders());
        $after = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['balance'];

        self::assertSame($before, $after, 'the money really moved, so the balances are already right');
    }

    public function testASecondCatchUpChangesNothing(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->detect();
        $second = $this->detect();

        self::assertSame(0, $second['matched']);
        self::assertSame(1, $second['scanned'], 'only the ordinary expense is still up for pairing');
    }
}
