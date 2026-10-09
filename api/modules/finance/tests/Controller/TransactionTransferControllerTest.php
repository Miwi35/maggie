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

class TransactionTransferControllerTest extends WebTestCase
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

    private function tx(string $ref): Transaction
    {
        /** @var Transaction $transaction */
        $transaction = $this->getFixture($ref);

        return $transaction;
    }

    private function id(string $ref): string
    {
        return (string) $this->tx($ref)->getId();
    }

    private function login(): void
    {
        $this->loadFixtures('transaction_transfer.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $path, ?array $body = null): array
    {
        $this->client->request(
            $method,
            $path,
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            null === $body ? '' : json_encode($body, JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function mark(string $ref, array $body): array
    {
        return $this->call('PUT', sprintf('/api/finance/transactions/%s/transfer', $this->id($ref)), $body);
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->tx($ref)->getId());
    }

    public function testUnauthenticatedReturns401OnEveryRoute(): void
    {
        $this->loadFixtures('transaction_transfer.yaml');
        $id = $this->id('transfer_out');

        foreach ([
            ['GET', "/api/finance/transactions/$id/transfer"],
            ['GET', "/api/finance/transactions/$id/transfer-candidates"],
            ['PUT', "/api/finance/transactions/$id/transfer"],
        ] as [$method, $path]) {
            $this->client->request($method, $path);
            self::assertResponseStatusCodeSame(401, "$method $path");
        }
    }

    public function testAnotherUsersOrUnknownTransactionIsReportedMissing(): void
    {
        $this->login();

        foreach ([$this->id('stranger_line'), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->call('GET', "/api/finance/transactions/$id/transfer");
            self::assertResponseStatusCodeSame(404, $id);

            $this->call('GET', "/api/finance/transactions/$id/transfer-candidates");
            self::assertResponseStatusCodeSame(404, $id);

            $this->call('PUT', "/api/finance/transactions/$id/transfer", ['transferKind' => 'internal']);
            self::assertResponseStatusCodeSame(404, $id);
        }

        self::assertSame(TransferKind::None, $this->stored('stranger_line')->getTransferKind());
    }

    public function testAnUnknownKindOrABrokenBodyReturns400(): void
    {
        $this->login();

        $this->mark('transfer_out', ['transferKind' => 'sideways']);
        self::assertResponseStatusCodeSame(400);

        $this->mark('transfer_out', []);
        self::assertResponseStatusCodeSame(400);

        $this->client->request(
            'PUT',
            '/api/finance/transactions/'.$this->id('transfer_out').'/transfer',
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            '{broken',
        );
        self::assertResponseStatusCodeSame(400);

        self::assertSame(TransferKind::None, $this->stored('transfer_out')->getTransferKind());
    }

    public function testACounterpartThatCannotBeOneReturns400AndWritesNothing(): void
    {
        $this->login();

        foreach ([
            'its own line' => $this->id('transfer_out'),
            'a line on the same account' => $this->id('same_account'),
            "somebody else's line" => $this->id('stranger_line'),
            'an unknown line' => '01ARZ3NDEKTSV4RRFFQ69G5FAV',
            'something that is no id' => 'not-a-ulid',
        ] as $case => $counterpartId) {
            $this->mark('transfer_out', ['transferKind' => 'internal', 'counterpartId' => $counterpartId]);
            self::assertResponseStatusCodeSame(400, $case);
        }

        $this->mark('transfer_out', ['transferKind' => 'none', 'counterpartId' => $this->id('transfer_in_far')]);
        self::assertResponseStatusCodeSame(400, 'a release has no counterpart');

        self::assertSame(TransferKind::None, $this->stored('transfer_out')->getTransferKind());
        self::assertMercureUpdateCount(0);
    }

    public function testMarkingChoosesTheCounterpartAndPairsBothLegsAsManual(): void
    {
        $this->login();

        $data = $this->mark('transfer_out', [
            'transferKind' => 'internal',
            'counterpartId' => $this->id('transfer_in_far'),
        ]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertSame('internal', $data['transferKind']);
        self::assertSame('manual', $data['transferSource']);
        self::assertSame($this->id('transfer_in_far'), $data['counterpart']['id']);
        self::assertSame('Virement du Livret', $data['counterpart']['label']);
        self::assertSame('Courant', $data['counterpart']['accountName']);
        self::assertSame('2026-09-10', $data['counterpart']['bookedAt']);
        self::assertSame(300000, $data['counterpart']['amountCents']);

        $out = $this->stored('transfer_out');
        $in = $this->stored('transfer_in_far');
        self::assertSame(TransferKind::Internal, $out->getTransferKind());
        self::assertSame(TransferKind::Internal, $in->getTransferKind());
        self::assertSame(TransferSource::Manual, $out->getTransferSource());
        self::assertSame(TransferSource::Manual, $in->getTransferSource());
        self::assertSame($this->id('transfer_in_far'), (string) $out->getCounterpart()?->getId());
        self::assertSame($this->id('transfer_out'), (string) $in->getCounterpart()?->getId());

        $this->assertMercureUpdatePublished($this->id('transfer_out'));
        $this->assertMercureUpdatePublished($this->id('transfer_in_far'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('transfer_out'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('transfer_in_far'));
    }

    public function testMarkingWithoutACounterpartMarksASingleLeg(): void
    {
        $this->login();

        $data = $this->mark('groceries', ['transferKind' => 'internal']);

        self::assertResponseIsSuccessful();
        self::assertSame('internal', $data['transferKind']);
        self::assertNull($data['counterpart']);

        $stored = $this->stored('groceries');
        self::assertSame(TransferKind::Internal, $stored->getTransferKind());
        self::assertSame(TransferSource::Manual, $stored->getTransferSource());
        self::assertNull($stored->getCounterpart());
    }

    public function testReleasingOneLegFreesBothAndTellsEveryScreen(): void
    {
        $this->login();
        $this->mark('close_out', ['transferKind' => 'internal', 'counterpartId' => $this->id('close_in')]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->mark('close_out', ['transferKind' => 'none']);

        self::assertResponseIsSuccessful();
        self::assertSame('none', $data['transferKind']);
        self::assertSame('manual', $data['transferSource']);
        self::assertNull($data['counterpart']);

        $out = $this->stored('close_out');
        $in = $this->stored('close_in');
        self::assertSame(TransferKind::None, $out->getTransferKind());
        self::assertSame(TransferKind::None, $in->getTransferKind());
        self::assertNull($in->getCounterpart());
        self::assertSame(TransferSource::Manual, $out->getTransferSource(), 'the line the owner judged is sealed');
        self::assertSame(TransferSource::Auto, $in->getTransferSource(), 'the other leg is only freed');

        $this->assertMercureUpdatePublished($this->id('close_out'));
        $this->assertMercureUpdatePublished($this->id('close_in'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('close_in'));
    }

    public function testAManualReleaseSurvivesTheCatchUpOfTheDetection(): void
    {
        $this->login();

        $this->mark('close_out', ['transferKind' => 'internal', 'counterpartId' => $this->id('close_in')]);
        $this->mark('close_out', ['transferKind' => 'none']);

        $detected = $this->call('POST', '/api/finance/internal-transfers/detect', []);

        self::assertSame(0, $detected['matched'], 'the sealed line is not paired again');
        self::assertSame(TransferKind::None, $this->stored('close_out')->getTransferKind());
        self::assertSame(TransferKind::None, $this->stored('close_in')->getTransferKind());
    }

    public function testAManualMarkingSurvivesTheCatchUpOfTheDetection(): void
    {
        $this->login();

        $this->mark('transfer_out', ['transferKind' => 'internal', 'counterpartId' => $this->id('transfer_in_far')]);
        $this->call('POST', '/api/finance/internal-transfers/detect', []);

        $out = $this->stored('transfer_out');
        self::assertSame(TransferKind::Internal, $out->getTransferKind());
        self::assertSame(TransferSource::Manual, $out->getTransferSource());
        self::assertSame($this->id('transfer_in_far'), (string) $out->getCounterpart()?->getId());
    }

    public function testShowReadsTheMarkingAndItsCounterpart(): void
    {
        $this->login();

        $plain = $this->call('GET', '/api/finance/transactions/'.$this->id('groceries').'/transfer');
        self::assertResponseIsSuccessful();
        self::assertSame(['transferKind' => 'none', 'transferSource' => 'auto', 'counterpart' => null], $plain);

        $this->mark('close_out', ['transferKind' => 'internal', 'counterpartId' => $this->id('close_in')]);
        $paired = $this->call('GET', '/api/finance/transactions/'.$this->id('close_in').'/transfer');

        self::assertSame('internal', $paired['transferKind']);
        self::assertSame($this->id('close_out'), $paired['counterpart']['id']);
        self::assertSame('Livret', $paired['counterpart']['accountName']);
    }

    public function testCandidatesAreTheOppositeAmountOnAnotherOwnAccountWithinTheWindow(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/'.$this->id('transfer_out').'/transfer-candidates');

        self::assertResponseIsSuccessful();
        self::assertSame(
            [$this->id('transfer_in_far')],
            array_column($data['candidates'], 'id'),
            'not the line on the same account, not another owner\'s, not the one 100 days away',
        );
        self::assertSame('Courant', $data['candidates'][0]['accountName']);
    }

    public function testACandidateTheOwnerReleasedByHandIsStillOffered(): void
    {
        $this->login();
        $this->mark('close_in', ['transferKind' => 'internal']);
        $this->mark('close_in', ['transferKind' => 'none']);

        $data = $this->call('GET', '/api/finance/transactions/'.$this->id('close_out').'/transfer-candidates');

        self::assertSame([$this->id('close_in')], array_column($data['candidates'], 'id'));
    }

    public function testALineThatHasNoCandidateGetsAnEmptyList(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/'.$this->id('groceries').'/transfer-candidates');

        self::assertResponseIsSuccessful();
        self::assertSame([], $data['candidates']);
    }

    public function testAnUnknownCandidateKindReturns400(): void
    {
        $this->login();

        foreach (['sideways', 'none'] as $kind) {
            $this->call('GET', '/api/finance/transactions/'.$this->id('transfer_out').'/transfer-candidates?kind='.$kind);
            self::assertResponseStatusCodeSame(400, $kind);
        }
    }

    public function testTheCandidatesOfARejectionAreOnTheSameAccount(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/'.$this->id('transfer_out').'/transfer-candidates?kind=rejected');

        self::assertResponseIsSuccessful();
        self::assertSame([$this->id('same_account')], array_column($data['candidates'], 'id'));
    }

    public function testMarkingARejectionByHandPairsBothLegsOnTheSameAccount(): void
    {
        $this->login();

        $data = $this->mark('transfer_out', ['transferKind' => 'rejected', 'counterpartId' => $this->id('same_account')]);

        self::assertResponseIsSuccessful();
        self::assertSame('rejected', $data['transferKind']);
        self::assertSame($this->id('same_account'), $data['counterpart']['id']);

        $debit = $this->stored('transfer_out');
        self::assertSame(TransferKind::Rejected, $debit->getTransferKind());
        self::assertSame(TransferSource::Manual, $debit->getTransferSource());
        self::assertSame(TransferKind::Rejected, $this->stored('same_account')->getTransferKind());
        self::assertSame($this->id('transfer_out'), (string) $this->stored('same_account')->getCounterpart()?->getId());

        $this->assertMercureUpdatePublished($this->id('transfer_out'));
        $this->assertMercureUpdatePublished($this->id('same_account'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('transfer_out'));
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, $this->id('same_account'));
    }

    public function testARejectionOnAnotherAccountReturns400AndWritesNothing(): void
    {
        $this->login();

        $this->mark('transfer_out', ['transferKind' => 'rejected', 'counterpartId' => $this->id('transfer_in_far')]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(TransferKind::None, $this->stored('transfer_out')->getTransferKind());
        self::assertMercureUpdateCount(0);
    }

    public function testReleasingARejectionFreesBothLegs(): void
    {
        $this->login();
        $this->mark('transfer_out', ['transferKind' => 'rejected', 'counterpartId' => $this->id('same_account')]);

        $this->mark('same_account', ['transferKind' => 'none']);

        self::assertResponseIsSuccessful();
        self::assertSame(TransferKind::None, $this->stored('transfer_out')->getTransferKind());
        self::assertSame(TransferKind::None, $this->stored('same_account')->getTransferKind());
    }
}
