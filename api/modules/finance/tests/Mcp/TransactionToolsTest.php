<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class TransactionToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreateExpensePersistsAndPublishes(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('create', accountId: (string) $account->getId(), amountCents: -1599, label: 'Boulangerie', bookedAt: '2026-07-08');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(-1599, $data['transaction']['amountCents']);
        self::assertSame('Boulangerie', $data['transaction']['label']);
        self::assertSame((string) $account->getId(), $data['transaction']['accountId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $transactions = $em->getRepository(Transaction::class)->findAll();
        self::assertCount(3, $transactions);

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testCreateRequiresAccountAndAmount(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('create', label: 'Orphan');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }

    public function testListTransactionsReturnsAll(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(2, $data['transactions']);
    }

    public function testCategorizeTransaction(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $transaction = $this->getFixture('salary');
        $category = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('categorize', transactionId: (string) $transaction->getId(), categoryId: (string) $category->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame((string) $category->getId(), $data['transaction']['categoryId']);

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testUpdateTransactionUpdatesAndPublishes(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $transaction = $this->getFixture('groceries');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('update', transactionId: (string) $transaction->getId(), amountCents: -5000, status: 'committed');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame(-5000, $data['transaction']['amountCents']);
        self::assertSame('committed', $data['transaction']['status']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Transaction::class, $transaction->getId());
        self::assertSame(-5000, $refreshed->getAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testClearRemovesTheCategory(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $transaction = $this->getFixture('groceries');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('update', transactionId: (string) $transaction->getId(), clear: ['categoryId', 'label']);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertNull($data['transaction']['categoryId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Transaction::class, $transaction->getId());
        self::assertNull($refreshed->getCategory());
        self::assertSame('Supermarché', $refreshed->getLabel(), 'Required fields cannot be cleared');
        self::assertSame(-4599, $refreshed->getAmountCents());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testClearOnAnUnknownTransactionReturnsAnError(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode($tool('update', transactionId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['categoryId']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testDeleteTransactionRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));
        $transaction = $this->getFixture('groceries');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('delete', transactionId: (string) $transaction->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Transaction::class, $transaction->getId()));

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchDeleteDispatched('transactions');
    }

    public function testAnUnknownActionIsReported(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode($tool('archive'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Unknown action', $data['error']);
    }

    public function testUpdateWithoutATransactionIdIsReported(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode($tool('update', label: 'Sans cible'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('transactionId is required', $data['error']);
    }

    public function testWithoutAUserBoundTheToolReportsTheError(): void
    {
        $this->loadFixtures('internal_transfers.yaml');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode($tool('list'), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testMarkingAnInternalTransferByHandPairsBothLegsAsTheUsersDecision(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $in->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('internal', $data['transaction']['transferKind']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        self::assertSame((string) $in->getId(), $data['transaction']['counterpartId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $storedIn = $em->find(Transaction::class, $in->getId());
        self::assertSame(TransferKind::Internal, $storedIn->getTransferKind());
        self::assertSame(TransferSource::Manual, $storedIn->getTransferSource());
        self::assertSame((string) $out->getId(), (string) $storedIn->getCounterpart()?->getId());

        // Both legs, not just the one the tool was pointed at: the list is
        // served from Elasticsearch, so a stale document is a missing badge.
        $this->assertMercureUpdatePublished((string) $out->getId());
        $this->assertMercureUpdatePublished((string) $in->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $out->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $in->getId());
    }

    public function testMarkingAPairBreaksTheOldOneAndRepublishesTheLineItFreed(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');
        $other = $this->getFixture('other_savings_debit');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('update', transactionId: (string) $in->getId(), transferKind: 'internal', counterpartId: (string) $other->getId());
        $this->resetMercure();
        $this->resetAsyncTransport();

        // The owner corrects himself: the credit really faces the debit.
        $tool('update', transactionId: (string) $in->getId(), transferKind: 'internal', counterpartId: (string) $out->getId());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $freed = $em->find(Transaction::class, $other->getId());
        self::assertFalse($freed->isInternalTransfer(), 'the line it no longer faces is freed, not left excluded');
        self::assertNull($freed->getCounterpart());
        self::assertSame(TransferSource::Auto, $freed->getTransferSource());
        $this->assertMercureUpdatePublished((string) $other->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $other->getId());
    }

    public function testUnmarkingAnInternalTransferSealsTheLineAgainstTheDetection(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $in->getId());
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'none'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('none', $data['transaction']['transferKind']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        self::assertNull($data['transaction']['counterpartId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $storedIn = $em->find(Transaction::class, $in->getId());
        self::assertSame(TransferKind::None, $storedIn->getTransferKind(), 'the other leg is released too');
        self::assertNull($storedIn->getCounterpart());

        $this->assertMercureUpdatePublished((string) $in->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $in->getId());
    }

    public function testClearTakesTheLineBackOutOfTheTransfers(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $in->getId());
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), clear: ['transferKind']),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('none', $data['transaction']['transferKind']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        self::assertNull($data['transaction']['counterpartId']);

        $this->assertMercureUpdatePublished((string) $in->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $in->getId());
    }

    public function testASingleLeggedTransferNeedsNoCounterpart(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('internal', $data['transaction']['transferKind']);
        self::assertNull($data['transaction']['counterpartId'], 'only one of the two accounts is known');
    }

    public function testASingleLeggedMarkingContradictsThePairingItReplaces(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $in->getId());
        $this->resetMercure();
        $this->resetAsyncTransport();

        // « It is a transfer, but not with that line. »
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('internal', $data['transaction']['transferKind']);
        self::assertNull($data['transaction']['counterpartId']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $storedIn = $em->find(Transaction::class, $in->getId());
        self::assertFalse($storedIn->isInternalTransfer(), 'the leg it no longer faces is freed');
        self::assertNull($storedIn->getCounterpart());

        $this->assertMercureUpdatePublished((string) $in->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $in->getId());
    }

    public function testACounterpartOfAnotherUserIsRefused(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $theirs = $this->getFixture('someone_elses_credit');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $theirs->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
        self::assertStringContainsString('Counterpart not found', $data['error']);
    }

    public function testALineCannotBeItsOwnCounterpart(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'internal', counterpartId: (string) $out->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testATransferBetweenTwoLinesOfTheSameAccountIsRefused(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $in = $this->getFixture('transfer_in');
        $groceries = $this->getFixture('groceries');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $in->getId(), transferKind: 'internal', counterpartId: (string) $groceries->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testAnUnknownTransferKindIsRefused(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'avance'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayHasKey('error', $data);
    }

    public function testCreatingTheSecondLegPairsItWithTheFirst(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $checking = $this->getFixture('checking');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->remove($em->find(Transaction::class, $this->getFixture('transfer_in')->getId()));
        $em->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('create', accountId: (string) $checking->getId(), amountCents: 300000, label: 'Virement du Livret', bookedAt: '2026-09-13'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('internal', $data['transaction']['transferKind']);
        self::assertSame('auto', $data['transaction']['transferSource']);
        self::assertSame((string) $out->getId(), $data['transaction']['counterpartId']);

        $em->clear();
        $storedOut = $em->find(Transaction::class, $out->getId());
        self::assertSame(TransferKind::Internal, $storedOut->getTransferKind());
        self::assertSame($data['transaction']['id'], (string) $storedOut->getCounterpart()?->getId());

        // Both legs changed, so both must reach the open screens and the index.
        $this->assertMercureUpdatePublished('/transactions/'.$out->getId());
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }
}
