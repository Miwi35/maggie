<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\Transaction;
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

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('create', label: 'Orphan');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }

    public function testListTransactionsReturnsAll(): void
    {
        $this->loadFixtures('transaction.yaml');

        $tool = self::getContainer()->get(ManageTransactionsTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(2, $data['transactions']);
    }

    public function testCategorizeTransaction(): void
    {
        $this->loadFixtures('transaction.yaml');
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

    public function testDeleteTransactionRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('transaction.yaml');
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
}
