<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Mcp\Tool\ManageAccountsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class AccountToolsTest extends KernelTestCase
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

    public function testCreateAccountPersistsAndPublishes(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('create', name: 'Compte courant', type: 'checking', bank: 'Crédit Agricole', balanceCents: 125000);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Compte courant', $data['account']['name']);
        self::assertSame('checking', $data['account']['type']);
        self::assertSame(125000, $data['account']['balanceCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $accounts = $em->getRepository(Account::class)->findAll();
        self::assertCount(1, $accounts);
        self::assertSame('Compte courant', $accounts[0]->getName());
        self::assertSame(125000, $accounts[0]->getBalanceCents());

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testCreateCushionAccount(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('create', name: 'Matelas', type: 'savings', isCushion: true);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertTrue($data['account']['isCushion']);
    }

    public function testListAccountsReturnsAll(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('list');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertCount(2, $data['accounts']);
    }

    public function testUpdateAccountUpdatesAndPublishes(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();

        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('update', accountId: (string) $account->getId(), name: 'Compte principal', balanceCents: 90000);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertSame('Compte principal', $data['account']['name']);
        self::assertSame(90000, $data['account']['balanceCents']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Account::class, $account->getId());
        self::assertSame('Compte principal', $refreshed->getName());
        self::assertSame(90000, $refreshed->getBalanceCents());

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testClearEmptiesTheBank(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('update', accountId: (string) $account->getId(), clear: ['bank', 'name']);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);
        self::assertNull($data['account']['bank']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Account::class, $account->getId());
        self::assertNull($refreshed->getBank());
        self::assertSame('Compte courant', $refreshed->getName(), 'Required fields cannot be cleared');
        self::assertSame(125000, $refreshed->getBalanceCents());

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testClearOnAnUnknownAccountReturnsAnError(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $data = json_decode($tool('update', accountId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', clear: ['bank']), true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayHasKey('error', $data);
    }

    public function testDeleteAccountRemovesPublishesAndDeletes(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));

        $account = $this->getFixture('checking');

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('delete', accountId: (string) $account->getId());

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Account::class, $account->getId()));

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertElasticsearchDeleteDispatched('accounts');
    }

    public function testUnknownActionReturnsError(): void
    {
        $this->loadFixtures('user.yaml');
        $this->loginFixtureUser();

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $result = $tool('frobnicate');

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $data);
    }

    public function testDeleteAccountRemovesItsTransactionsFromTheIndex(): void
    {
        $this->loadFixtures('transaction.yaml');
        $this->loginFixtureUser();
        $this->loginUser($this->getFixture('test_user'));

        $account = $this->getFixture('checking');
        $transactionIds = [
            (string) $this->getFixture('groceries')->getId(),
            (string) $this->getFixture('salary')->getId(),
        ];

        $tool = self::getContainer()->get(ManageAccountsTool::class);
        $data = json_decode($tool('delete', accountId: (string) $account->getId()), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success']);

        $deleted = $this->deletedDocuments();
        foreach ($transactionIds as $id) {
            self::assertContains(['transactions', $id], $deleted);
        }
    }

    /** @return array<int, array{string, string}> */
    private function deletedDocuments(): array
    {
        $deleted = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof DeleteDocumentCommand) {
                $deleted[] = [$message->indexName, $message->documentId];
            }
        }

        return $deleted;
    }
}
