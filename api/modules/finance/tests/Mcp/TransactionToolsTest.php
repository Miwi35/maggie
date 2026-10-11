<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
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
        $category = $this->getFixture('paycheck');

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

    public function testCreateReadsTheCounterpartyFromTheLabelAndExposesIt(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $created = json_decode(
            $tool('create', accountId: (string) $account->getId(), amountCents: -1349, label: 'PRLV SEPA NETFLIX.COM 12/10', bookedAt: '2026-10-12'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('PRLV SEPA NETFLIX.COM', $created['transaction']['counterpartyName']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Transaction::class, $created['transaction']['id']);
        self::assertSame('prlv sepa netflixcom', $stored->getCounterpartyKey());

        $listed = json_decode($tool('list'), true, 512, JSON_THROW_ON_ERROR);
        $names = array_column($listed['transactions'], 'counterpartyName');
        self::assertContains('PRLV SEPA NETFLIX.COM', $names);
    }

    public function testRewritingTheLabelOfAReadCounterpartyFollowsIt(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $created = json_decode(
            $tool('create', accountId: (string) $account->getId(), amountCents: -1349, label: 'PRLV SEPA NETFLIX.COM 12/10', bookedAt: '2026-10-12'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        $updated = json_decode(
            $tool('update', transactionId: $created['transaction']['id'], label: 'PRLV SEPA SPOTIFY 12/10'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('PRLV SEPA SPOTIFY', $updated['transaction']['counterpartyName']);
    }

    public function testRewritingTheLabelKeepsAPayeeTheBankNamed(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $transaction = $this->getFixture('groceries');
        $transaction->setCounterpartyName('CARREFOUR MARKET');
        self::getContainer()->get('doctrine.orm.entity_manager')->flush();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $updated = json_decode(
            $tool('update', transactionId: (string) $transaction->getId(), label: 'Courses du samedi'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('Courses du samedi', $updated['transaction']['label']);
        self::assertSame('CARREFOUR MARKET', $updated['transaction']['counterpartyName']);
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

    public function testCreatingARejectionPairsItWithThePaymentItGivesBackAndNeverAsATransfer(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $savings = $this->getFixture('savings');

        // The credit lands on the Livret, the exact opposite of the debit of
        // the day before: the bank gave the transfer back.
        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('create', accountId: (string) $savings->getId(), amountCents: 300000, label: 'REJET VIREMENT VERS COURANT', bookedAt: '2026-09-13'),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        self::assertSame('rejected', $data['transaction']['transferKind']);
        self::assertSame((string) $out->getId(), $data['transaction']['counterpartId']);
        self::assertSame('Rejet de Virement vers Courant du 2026-09-12', $data['transaction']['transferNote']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $storedOut = $em->find(Transaction::class, $out->getId());
        self::assertSame(TransferKind::Rejected, $storedOut->getTransferKind());
        self::assertSame($data['transaction']['id'], (string) $storedOut->getCounterpart()?->getId());
        self::assertSame(TransferKind::None, $em->find(Transaction::class, $this->getFixture('transfer_in')->getId())->getTransferKind());

        $this->assertMercureUpdatePublished('/transactions/'.$out->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $out->getId());
    }

    public function testCreatingARecentRejectionRaisesOneNotification(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $checking = (string) $this->getFixture('checking')->getId();
        $day = new \DateTimeImmutable('-3 days');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('create', accountId: $checking, amountCents: -9988, label: 'PRELEVEMENT EURO-ASSURANCE', bookedAt: $day->modify('-1 day')->format('Y-m-d'));
        $data = json_decode(
            $tool('create', accountId: $checking, amountCents: 9988, label: 'REJET PRLV EURO-ASSURANCE', bookedAt: $day->format('Y-m-d')),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('rejected', $data['transaction']['transferKind']);

        $notifications = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getRepository(Notification::class)
            ->findBy(['relatedEntityIri' => '/api/transactions/'.$data['transaction']['id']]);
        self::assertCount(1, $notifications);
        self::assertSame(NotificationType::Finance, $notifications[0]->getType());
        self::assertStringStartsWith('Prélèvement EURO-ASSURANCE de 99,88 € rejeté le ', $notifications[0]->getTitle());
    }

    public function testMarkingARejectionByHandSaysRejectedOnThePayment(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $payment = $this->getFixture('groceries');
        $back = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $payment->getId(), transferKind: 'rejected', counterpartId: (string) $back->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame('rejected', $data['transaction']['transferKind']);
        self::assertSame('manual', $data['transaction']['transferSource']);
        self::assertSame('Rejeté', $data['transaction']['transferNote']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(TransferKind::Rejected, $em->find(Transaction::class, $back->getId())->getTransferKind());
        $this->assertMercureUpdatePublished((string) $back->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $back->getId());
    }

    public function testARejectionAcrossTwoAccountsIsRefused(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $out = $this->getFixture('transfer_out');
        $in = $this->getFixture('transfer_in');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('update', transactionId: (string) $out->getId(), transferKind: 'rejected', counterpartId: (string) $in->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertStringContainsString('account of the payment', $data['error'] ?? '');
        self::assertMercureUpdateCount(0);
    }

    public function testCreateAnIncomeInAnIncomeCategoryIsStored(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');
        $paycheck = $this->getFixture('paycheck');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('create', accountId: (string) $account->getId(), amountCents: 180000, label: 'Prime', bookedAt: '2026-07-20', categoryId: (string) $paycheck->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertTrue($data['success']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->getRepository(Transaction::class)->findOneBy(['label' => 'Prime']);
        self::assertSame('Salaire', $stored->getCategory()?->getName());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testCreateRefusesAnIncomeCategoryOnAnExpense(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');
        $paycheck = $this->getFixture('paycheck');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = json_decode(
            $tool('create', accountId: (string) $account->getId(), amountCents: -500, label: 'Erreur', bookedAt: '2026-07-20', categoryId: (string) $paycheck->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertStringContainsString('income category', $data['error']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(Transaction::class)->findAll());
    }

    public function testUpdateAndCategorizeRefuseAnExpenseCategoryOnAnIncome(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $salary = $this->getFixture('salary');
        $food = $this->getFixture('food');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $categorized = json_decode($tool('categorize', transactionId: (string) $salary->getId(), categoryId: (string) $food->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertStringContainsString('expense category', $categorized['error']);

        $updated = json_decode($tool('update', transactionId: (string) $salary->getId(), categoryId: (string) $food->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertStringContainsString('expense category', $updated['error']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Transaction::class, $salary->getId())->getCategory());
    }

    /** @return array<string, mixed> */
    private function listed(ManageTransactionsTool $tool, mixed ...$arguments): array
    {
        return json_decode($tool('list', ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function seedHistory(int $count): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $account = $this->getFixture('checking');
        $user = $this->getFixture('test_user');

        for ($i = 0; $i < $count; ++$i) {
            $transaction = (new Transaction())
                ->setUser($user)
                ->setAccount($account)
                ->setAmountCents(-1000 - $i)
                ->setCurrency('EUR')
                ->setBookedAt((new \DateTimeImmutable('2026-06-30'))->modify("-{$i} days"))
                ->setLabel(sprintf('Ligne %03d', $i))
                ->setStatus(TransactionStatus::Spent);
            $em->persist($transaction);
        }
        $em->flush();
    }

    public function testListIsBoundedAndSaysHowManyMoreThereAre(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $this->seedHistory(300);

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = $this->listed($tool);

        self::assertCount(30, $data['transactions']);
        self::assertSame(302, $data['total']);
        self::assertSame('Salaire', $data['transactions'][0]['label'], 'newest first');
        self::assertSame('2026-07-05', $data['transactions'][0]['bookedAt']);
    }

    public function testListLeavesTheRejectedPaymentsOutUnlessAskedFor(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $account = $this->getFixture('checking');
        $user = $this->getFixture('test_user');
        $make = static fn (int $cents, string $label): Transaction => (new Transaction())
            ->setUser($user)
            ->setAccount($account)
            ->setAmountCents($cents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-07-06'))
            ->setLabel($label)
            ->setStatus(TransactionStatus::Spent);
        $debit = $make(-20600, 'PRELEVEMENT EDF');
        $credit = $make(20600, 'REJET PRLV ELECTRICITE DE FRANCE');
        $debit->markAsRejection($credit, TransferSource::Auto);
        $em->persist($debit);
        $em->persist($credit);
        $this->flushWithoutTransactionEffects($em);

        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $default = $this->listed($tool);
        self::assertNotContains('PRELEVEMENT EDF', array_column($default['transactions'], 'label'));
        self::assertNotContains('REJET PRLV ELECTRICITE DE FRANCE', array_column($default['transactions'], 'label'));
        self::assertSame(2, $default['total']);

        $rejected = $this->listed($tool, transferKind: 'rejected');
        self::assertSame(2, $rejected['total']);
        self::assertEqualsCanonicalizing(
            ['PRELEVEMENT EDF', 'REJET PRLV ELECTRICITE DE FRANCE'],
            array_column($rejected['transactions'], 'label'),
        );

        self::assertSame(2, $this->listed($tool, transferKind: 'none')['total']);
        self::assertArrayHasKey('error', $this->listed($tool, transferKind: 'sideways'));
    }

    public function testListLimitIsHonouredAndCappedAtOneHundred(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $this->seedHistory(300);

        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        self::assertCount(5, $this->listed($tool, limit: 5)['transactions']);
        self::assertCount(100, $this->listed($tool, limit: 5000)['transactions']);
        self::assertCount(30, $this->listed($tool, limit: 0)['transactions']);
    }

    public function testListFiltersByDirectionDatesAccountAndText(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $tool('create', accountId: (string) $account->getId(), amountCents: -1349, label: 'PRLV SEPA NETFLIX.COM 12/10', bookedAt: '2026-10-12');

        $expenses = $this->listed($tool, direction: 'expense');
        self::assertSame(2, $expenses['total']);
        self::assertSame(['PRLV SEPA NETFLIX.COM 12/10', 'Supermarché'], array_column($expenses['transactions'], 'label'));

        $incomes = $this->listed($tool, direction: 'income');
        self::assertSame(['Salaire'], array_column($incomes['transactions'], 'label'));

        $july = $this->listed($tool, fromDate: '2026-07-01', toDate: '2026-07-31');
        self::assertSame(2, $july['total']);

        $byText = $this->listed($tool, query: 'netflix');
        self::assertSame(1, $byText['total']);
        self::assertSame('PRLV SEPA NETFLIX.COM 12/10', $byText['transactions'][0]['label']);

        $onAccount = $this->listed($tool, accountId: (string) $account->getId());
        self::assertSame(3, $onAccount['total']);
        self::assertSame(0, $this->listed($tool, accountId: '01ARZ3NDEKTSV4RRFFQ69G5FAV')['total']);
    }

    public function testListTextAlsoMatchesTheCounterpartyAndTreatsWildcardsLiterally(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $groceries = $this->getFixture('groceries');
        $groceries->setCounterpartyName('CARREFOUR MARKET');
        $em->flush();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        self::assertSame(['Supermarché'], array_column($this->listed($tool, query: 'carrefour')['transactions'], 'label'));
        self::assertSame(0, $this->listed($tool, query: '%')['total']);
    }

    public function testListRefusesAMalformedFilter(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        self::assertArrayHasKey('error', $this->listed($tool, fromDate: 'hier'));
        self::assertArrayHasKey('error', $this->listed($tool, toDate: '2026-13-45'));
        self::assertArrayHasKey('error', $this->listed($tool, direction: 'sideways'));
    }

    public function testListNeverShowsAnotherUsersTransactions(): void
    {
        $this->loadFixtures('internal_transfers.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $data = $this->listed($tool, limit: 100, query: 'crédit');

        self::assertSame(0, $data['total']);
        self::assertSame([], $data['transactions']);
        self::assertSame(
            0,
            $this->listed($tool, accountId: (string) $this->getFixture('other_checking')->getId())['total'],
        );
    }

    public function testListFiltersByCategoryAndTakesItsSubCategoriesAlong(): void
    {
        $this->loadFixtures('category_filter.yaml');
        $this->loginFixtureUser();
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $salary = $this->listed($tool, categoryId: (string) $this->getFixture('salary_category')->getId());
        self::assertSame(3, $salary['total']);
        self::assertSame(
            ['VIR SALAIRE SEPTEMBRE', 'VIR SALAIRE AOUT', 'VIR PRIME'],
            array_column($salary['transactions'], 'label'),
        );

        $bonus = $this->listed($tool, categoryId: (string) $this->getFixture('bonus_category')->getId());
        self::assertSame(['VIR PRIME'], array_column($bonus['transactions'], 'label'));

        $food = $this->listed($tool, categoryId: (string) $this->getFixture('food_category')->getId());
        self::assertSame(['Supermarché'], array_column($food['transactions'], 'label'));
    }

    public function testListByCategoryWithLimitOneReturnsTheMostRecent(): void
    {
        $this->loadFixtures('category_filter.yaml');
        $this->loginFixtureUser();
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $latest = $this->listed($tool, limit: 1, categoryId: (string) $this->getFixture('salary_category')->getId());

        self::assertSame(3, $latest['total']);
        self::assertSame(['VIR SALAIRE SEPTEMBRE'], array_column($latest['transactions'], 'label'));
    }

    public function testListByCategoryCombinesWithTheOtherFilters(): void
    {
        $this->loadFixtures('category_filter.yaml');
        $this->loginFixtureUser();
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        $data = $this->listed($tool, categoryId: (string) $this->getFixture('salary_category')->getId(), fromDate: '2026-08-01', direction: 'income');

        self::assertSame(2, $data['total']);
    }

    public function testListByAnUnknownOrForeignCategoryIsAnErrorNotAnEmptyList(): void
    {
        $this->loadFixtures('category_filter.yaml');
        $this->loginFixtureUser();
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        foreach (['01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid', (string) $this->getFixture('other_salary_category')->getId()] as $categoryId) {
            $data = $this->listed($tool, categoryId: $categoryId);
            self::assertArrayHasKey('error', $data, $categoryId);
            self::assertArrayNotHasKey('transactions', $data, $categoryId);
        }
    }
}
