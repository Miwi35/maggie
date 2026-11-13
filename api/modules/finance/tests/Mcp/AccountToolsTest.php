<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
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

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function bankAccount(?User $owner = null, string $currency = 'EUR'): Account
    {
        $owner ??= $this->getFixture('test_user');
        $connection = (new BankConnection())->setUser($owner)->setBankName('N26');
        $connection->activate('session-1', new \DateTimeImmutable('+30 days'));
        $this->em()->persist($connection);

        $account = (new Account())
            ->setUser($owner)
            ->setName('N26 Main')
            ->setBank('N26')
            ->setCurrency($currency)
            ->setBalanceCents(98700)
            ->setExternalAccountId('uid-n26')
            ->setExternalKey('iban-n26')
            ->setBankConnection($connection);
        $this->em()->persist($account);
        $this->em()->flush();

        return $account;
    }

    private function line(Account $account, string $label, int $amountCents, string $day, ?string $reference = null): Transaction
    {
        $line = (new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->setLabel($label)
            ->setAmountCents($amountCents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable($day))
            ->setStatus(TransactionStatus::Spent)
            ->setExternalId($reference);
        $this->em()->persist($line);
        $this->em()->flush();

        return $line;
    }

    /** @return array<string, mixed> */
    private function link(Account $kept, Account $bank, bool $confirm): array
    {
        $tool = self::getContainer()->get(ManageAccountsTool::class);

        return json_decode($tool('link', accountId: (string) $kept->getId(), bankAccountId: (string) $bank->getId(), confirm: $confirm), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testLinkWithoutConfirmWritesNothingAndPreviews(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->getFixture('checking');
        $bank = $this->bankAccount();
        $this->line($kept, 'CARTE BOULANGERIE', -450, '2026-09-20');
        $this->line($bank, 'CARTE BOULANGERIE', -450, '2026-09-20', 'tx-1');
        $this->line($bank, 'VIREMENT SALAIRE', 235000, '2026-10-06', 'tx-2');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->link($kept, $bank, false);

        self::assertSame(['success' => true, 'kept' => (string) $kept->getId(), 'moved' => 1, 'dropped' => 1, 'linked' => false], $data);
        $this->em()->clear();
        self::assertCount(3, $this->em()->getRepository(Account::class)->findAll());
        self::assertCount(3, $this->em()->getRepository(Transaction::class)->findAll());
        self::assertNull($this->em()->find(Account::class, $kept->getId())->getBankConnection());
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testLinkFoldsTheBankAccountIntoTheHandTypedOne(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->getFixture('checking');
        $keptId = (string) $kept->getId();
        $bank = $this->bankAccount();
        $bankId = (string) $bank->getId();
        $connectionId = (string) $bank->getBankConnection()->getId();

        $data = $this->link($kept, $bank, true);

        self::assertTrue($data['linked']);
        $this->em()->clear();
        self::assertCount(2, $this->em()->getRepository(Account::class)->findAll());
        $account = $this->em()->find(Account::class, $keptId);
        self::assertSame($keptId, (string) $account->getId());
        self::assertSame('Compte courant', $account->getName());
        self::assertSame('checking', $account->getType()->value);
        self::assertSame('uid-n26', $account->getExternalAccountId());
        self::assertSame('iban-n26', $account->getExternalKey());
        self::assertSame(98700, $account->getBalanceCents());
        self::assertSame($connectionId, (string) $account->getBankConnection()->getId());
        self::assertNull($this->em()->find(Account::class, $bankId));
    }

    public function testLinkDropsTwinMovementsAndKeepsTheirCategory(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->getFixture('checking');
        $bank = $this->bankAccount();
        $category = (new \Maggie\Finance\Entity\Category())->setName('Énergie')->setUser($this->getFixture('test_user'));
        $this->em()->persist($category);
        $handTyped = $this->line($kept, 'PRELEVEMENT EDF', -20600, '2026-10-05');
        $handTyped->setCategory($category);
        $this->em()->flush();
        $this->line($bank, 'PRELEVEMENT EDF', -20600, '2026-10-05', 'tx-edf');
        $this->line($bank, 'VIREMENT SALAIRE', 235000, '2026-10-06', 'tx-salary');

        $this->link($kept, $bank, true);

        $this->em()->clear();
        $lines = $this->em()->getRepository(Transaction::class)->findAll();
        self::assertCount(2, $lines);
        foreach ($lines as $line) {
            self::assertSame((string) $kept->getId(), (string) $line->getAccount()->getId());
        }
        $edf = $this->em()->getRepository(Transaction::class)->findOneBy(['label' => 'PRELEVEMENT EDF']);
        self::assertSame($category->getId()->toRfc4122(), $edf->getCategory()?->getId()->toRfc4122());
    }

    public function testLinkRepointsAdvancesToTheKeptAccount(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->getFixture('checking');
        $livret = $this->getFixture('savings');
        $bank = $this->bankAccount();
        $advance = $this->line($livret, 'VIR LIVRET', 50000, '2026-10-01');
        $advance->setTransferKind(TransferKind::Advance)->setAdvanceSource($bank);
        $this->em()->flush();
        $advanceId = $advance->getId();

        $this->link($kept, $bank, true);

        $this->em()->clear();
        $line = $this->em()->find(Transaction::class, $advanceId);
        self::assertSame((string) $kept->getId(), $line->getAdvanceSourceAccountId());
    }

    public function testLinkRefusesAKeptAccountAlreadyLinked(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->bankAccount();
        $other = $this->bankAccount();

        $data = $this->link($kept, $other, true);

        self::assertStringContainsString('already linked', $data['error']);
        self::assertStringContainsString('"linkedToBank":true', $data['error']);
        $this->em()->clear();
        self::assertCount(4, $this->em()->getRepository(Account::class)->findAll());
    }

    public function testLinkRefusesAnAbsorbedAccountWithoutBank(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();

        $data = $this->link($this->getFixture('checking'), $this->getFixture('savings'), true);

        self::assertStringContainsString('not linked to a bank', $data['error']);
        $this->em()->clear();
        self::assertCount(2, $this->em()->getRepository(Account::class)->findAll());
    }

    public function testLinkRefusesDifferentCurrencies(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $bank = $this->bankAccount(currency: 'USD');

        $data = $this->link($this->getFixture('checking'), $bank, true);

        self::assertStringContainsString('same currency', $data['error']);
        $this->em()->clear();
        self::assertCount(3, $this->em()->getRepository(Account::class)->findAll());
    }

    public function testLinkRefusesAnotherUsersAccount(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $stranger = (new User())->setEmail('stranger@example.com')->setGoogleId('google-stranger')->setName('Stranger');
        $this->em()->persist($stranger);
        $this->em()->flush();
        $bank = $this->bankAccount($stranger);

        $data = $this->link($this->getFixture('checking'), $bank, true);

        self::assertStringContainsString('Account not found', $data['error']);
        $this->em()->clear();
        self::assertNotNull($this->em()->find(Account::class, $bank->getId()));
    }

    public function testLinkPublishesAndIndexes(): void
    {
        $this->loadFixtures('account.yaml');
        $this->loginFixtureUser();
        $kept = $this->getFixture('checking');
        $bank = $this->bankAccount();
        $this->line($bank, 'VIREMENT SALAIRE', 235000, '2026-10-06', 'tx-salary');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->link($kept, $bank, true);

        $this->assertMercureUpdatePublished('/accounts/');
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Account::class);
        $this->assertElasticsearchIndexDispatched(Transaction::class);
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
