<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Command\MergeDuplicateAccountsCommand;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\MergeDuplicateAccounts;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * `app:finance:merge-duplicate-accounts` — folds the copies renewed consents
 * made of one bank account back into the oldest (MAG-351).
 */
final class MergeDuplicateAccountsCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private string $keyPath;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->keyPath = tempnam(sys_get_temp_dir(), 'eb-key-');
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        file_put_contents($this->keyPath, $pem);
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
        parent::tearDown();
    }

    /**
     * The command, against a bank whose live session lists these accounts —
     * or, given null, has forgotten the session.
     *
     * @param ?list<array<string, mixed>> $listed
     */
    private function tester(?array $listed = []): CommandTester
    {
        $http = new MockHttpClient(static fn (string $method, string $url) => str_contains($url, '/sessions/') && null !== $listed
            ? new MockResponse(json_encode(['accounts_data' => $listed], JSON_THROW_ON_ERROR))
            : new MockResponse('', ['http_code' => 404]));

        $container = self::getContainer();
        $useCase = new MergeDuplicateAccounts(
            new EnableBankingClient($http, 'app-id', $this->keyPath, 'https://api.example.test'),
            $container->get(AccountRepository::class),
            $container->get(BankConnectionRepository::class),
            $container->get(TransactionRepository::class),
            $this->em(),
            $container->get(EntityBroadcaster::class),
        );

        $application = new Application();
        $application->add(new MergeDuplicateAccountsCommand($useCase));

        return new CommandTester($application->find('app:finance:merge-duplicate-accounts'));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function owner(): User
    {
        return $this->em()->find(User::class, $this->getFixture('owner')->getId());
    }

    private function connection(?string $sessionId = null): BankConnection
    {
        $connection = (new BankConnection())->setUser($this->owner())->setBankName('Société Générale');
        if (null !== $sessionId) {
            $connection->activate($sessionId, new \DateTimeImmutable('+30 days'));
        }
        $this->em()->persist($connection);
        $this->em()->flush();

        return $connection;
    }

    private function account(BankConnection $connection, string $uid, int $balanceCents = -20167, ?string $key = null, string $name = 'M. CADARE MEVEN'): Account
    {
        $account = (new Account())
            ->setUser($this->owner())
            ->setName($name)
            ->setBank($connection->getBankName())
            ->setCurrency('EUR')
            ->setBalanceCents($balanceCents)
            ->setExternalAccountId($uid)
            ->setExternalKey($key)
            ->setBankConnection($connection);
        $this->em()->persist($account);
        $this->em()->flush();

        return $account;
    }

    private function movement(Account $account, string $label, int $amountCents, string $day, ?string $reference = null): Transaction
    {
        $transaction = (new Transaction())
            ->setUser($this->owner())
            ->setAccount($account)
            ->setLabel($label)
            ->setAmountCents($amountCents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable($day))
            ->setStatus(TransactionStatus::Spent)
            ->setExternalId($reference);
        $this->em()->persist($transaction);
        $this->em()->flush();

        return $transaction;
    }

    /**
     * The owner's history as it stands in production: the original account
     * with its history, and a copy a renewed consent made that holds the
     * recent movements a second time, plus one the original never got.
     *
     * @return array{0: Account, 1: Account}
     */
    private function duplicatedHistory(): array
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        $connection = $this->connection();
        $original = $this->account($connection, 'uid-expired-session-1');
        $copy = $this->account($connection, 'uid-expired-session-2');

        $this->movement($original, 'CARTE BOULANGERIE', -450, '2026-09-20');
        $this->movement($original, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        $categorised = $this->movement($copy, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05', 'bank-tx-edf');
        $categorised->assignCategory($this->em()->find(Category::class, $this->getFixture('energy')->getId()), CategorySource::Manual);
        $this->movement($copy, 'VIREMENT SALAIRE', 235000, '2026-10-06', 'bank-tx-salary');
        $this->em()->flush();

        $this->resetMercure();
        $this->resetAsyncTransport();

        return [$original, $copy];
    }

    /** @return list<Account> */
    private function accounts(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Account::class)->findBy(['user' => $this->getFixture('owner')->getId()]);
    }

    /** @return list<Transaction> */
    private function transactions(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Transaction::class)->findBy(['user' => $this->getFixture('owner')->getId()]);
    }

    public function testADryRunReportsTheMergeAndWritesNothing(): void
    {
        [$original, $copy] = $this->duplicatedHistory();

        $tester = $this->tester();
        $tester->execute(['--dry-run' => true]);
        $tester->assertCommandIsSuccessful();

        $display = $tester->getDisplay();
        self::assertStringContainsString((string) $original->getId(), $display);
        self::assertStringContainsString((string) $copy->getId(), $display);
        self::assertStringContainsString('Dry run only', $display);

        self::assertCount(2, $this->accounts());
        self::assertCount(4, $this->transactions());
        $this->assertMercureUpdateCount(0);
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    public function testTheCopiesFoldIntoTheOldestAccountWithoutTheirDuplicateMovements(): void
    {
        [$original, $copy] = $this->duplicatedHistory();

        $tester = $this->tester();
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        $accounts = $this->accounts();
        self::assertCount(1, $accounts, 'one real account, one Account');
        self::assertTrue($original->getId()->equals($accounts[0]->getId()), 'the oldest survives');
        self::assertSame('uid-expired-session-2', $accounts[0]->getExternalAccountId(), 'it follows the newest session');

        $transactions = $this->transactions();
        self::assertCount(3, $transactions, 'the EDF debit is kept once, the salary only the copy held is moved');
        $labels = [];
        foreach ($transactions as $transaction) {
            self::assertTrue($original->getId()->equals($transaction->getAccount()->getId()));
            $labels[] = $transaction->getLabel();
        }
        sort($labels);
        self::assertSame(['CARTE BOULANGERIE', 'PRELEVEMENT ELECTRICITE DE FRANCE', 'VIREMENT SALAIRE'], $labels);

        $edf = $this->em()->getRepository(Transaction::class)->findOneBy(['label' => 'PRELEVEMENT ELECTRICITE DE FRANCE']);
        self::assertSame('Énergie', $edf?->getCategory()?->getName(), 'the category set on the dropped copy passes to the one kept');
        self::assertSame('bank-tx-edf', $edf->getExternalId(), 'so does the bank reference');

        // Straight to the database: the lists read the index, the open screens Mercure.
        $this->assertMercureUpdatePublished('/accounts/'.$copy->getId());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchDeleteDispatched('accounts');
        $this->assertElasticsearchDeleteDispatched('transactions');
        $this->assertElasticsearchIndexDispatchedFor(Account::class, (string) $original->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $edf->getId());
    }

    public function testARejectionPairedOnTheCopyStaysARejectionOnTheAccountKept(): void
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        $connection = $this->connection();
        $original = $this->account($connection, 'uid-expired-session-1');
        $copy = $this->account($connection, 'uid-expired-session-2');

        // The copy's sync paired the rejection (MAG-350); the original still
        // holds the same two lines, unpaired.
        $this->movement($original, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        $this->movement($original, 'REJET PRLV ELECTRICITE DE FRANCE', 20600, '2026-10-06');
        $debit = $this->movement($copy, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        $credit = $this->movement($copy, 'REJET PRLV ELECTRICITE DE FRANCE', 20600, '2026-10-06');
        $debit->markAsRejection($credit, TransferSource::Auto);
        $this->em()->flush();

        $tester = $this->tester();
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        $transactions = $this->transactions();
        self::assertCount(2, $transactions);
        foreach ($transactions as $transaction) {
            self::assertSame(TransferKind::Rejected, $transaction->getTransferKind(), $transaction->getLabel());
            self::assertNotNull($transaction->getCounterpart(), $transaction->getLabel());
            self::assertTrue($original->getId()->equals($transaction->getCounterpart()->getAccount()->getId()));
        }
    }

    public function testRunningItAgainMergesNothingMore(): void
    {
        $this->duplicatedHistory();

        $this->tester()->execute([]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $tester = $this->tester();
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        self::assertStringContainsString('Aucun compte en double.', $tester->getDisplay());
        self::assertCount(1, $this->accounts());
        self::assertCount(3, $this->transactions());
        $this->assertMercureUpdateCount(0);
    }

    public function testASessionTheProviderNoLongerKnowsIsAWarningNotAFailure(): void
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        // The provider answers a forgotten session with a 404 and no JSON body.
        $connection = $this->connection('forgotten-session');
        $this->account($connection, 'uid-old');
        $this->account($connection, 'uid-new');

        $tester = $this->tester(null);
        $tester->execute(['--dry-run' => true]);
        $tester->assertCommandIsSuccessful();

        self::assertStringContainsString('session illisible', $tester->getDisplay());
        self::assertStringNotContainsString('Aucun compte en double.', $tester->getDisplay(), 'the lookalikes are still found');
    }

    public function testACopyWhoseBalanceFrozeWithItsSessionIsKnownByItsMovements(): void
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        // No live session, and the copy's balance stopped at its last sync:
        // only the movements both hold say it is the same account.
        $connection = $this->connection();
        $original = $this->account($connection, 'uid-expired-session-1', -15000);
        $copy = $this->account($connection, 'uid-expired-session-2', -20167);
        foreach ([$original, $copy] as $account) {
            $this->movement($account, 'VIREMENT SALAIRE', 235000, '2026-10-01');
            $this->movement($account, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        }
        $this->movement($copy, 'PRLV SEPA EDF', -20600, '2026-10-05');

        $tester = $this->tester(null);
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        $accounts = $this->accounts();
        self::assertCount(1, $accounts);
        self::assertTrue($original->getId()->equals($accounts[0]->getId()));
        self::assertSame(-20167, $accounts[0]->getBalanceCents(), 'the newest copy holds the latest balance');
        self::assertCount(3, $this->transactions(), 'the second EDF line of the copy is a movement of its own');
        $this->assertMercureUpdatePublished('/accounts/'.$copy->getId());
    }

    public function testTwoAccountsOfTheSameNameWithTheirOwnMovementsStayApart(): void
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        $connection = $this->connection();
        $checking = $this->account($connection, 'uid-checking', 28434, null, 'Meven Cadare');
        $savings = $this->account($connection, 'uid-savings', 40, null, 'Meven Cadare');
        $this->movement($checking, 'CARTE BOULANGERIE', -450, '2026-10-02');
        $this->movement($checking, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        $this->movement($checking, 'COTISATION CARTE', -200, '2026-10-06');
        $this->movement($savings, 'INTERETS', 40, '2026-10-03');
        $this->movement($savings, 'COTISATION CARTE', -200, '2026-10-06');
        $this->movement($savings, 'VERSEMENT', 1000, '2026-10-07');

        $tester = $this->tester(null);
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        self::assertStringContainsString('Aucun compte en double.', $tester->getDisplay(), 'one fee in common is not a shared history');
        self::assertCount(2, $this->accounts());
        self::assertCount(6, $this->transactions());
    }

    public function testTheLiveSessionTellsCopiesApartFromTwoAccountsOfTheSameName(): void
    {
        $this->loadFixtures('MergeDuplicateAccountsCommandTest.yaml');

        // Two real accounts, same holder name, and a copy of the first whose
        // balance has since moved: only the bank's identification tells.
        $connection = $this->connection('live-session');
        $checking = $this->account($connection, 'uid-old', -20167, 'hash-checking', 'Meven Cadare');
        $savings = $this->account($connection, 'uid-savings', 40, null, 'Meven Cadare');
        $copy = $this->account($connection, 'uid-live', 28434, null, 'Meven Cadare');

        $tester = $this->tester([
            ['uid' => 'uid-live', 'identification_hash' => 'hash-checking'],
            ['uid' => 'uid-savings', 'identification_hash' => 'hash-savings'],
        ]);
        $tester->execute([]);
        $tester->assertCommandIsSuccessful();

        $byId = [];
        foreach ($this->accounts() as $account) {
            $byId[(string) $account->getId()] = $account;
        }

        self::assertCount(2, $byId);
        self::assertArrayNotHasKey((string) $copy->getId(), $byId);
        self::assertSame('uid-live', $byId[(string) $checking->getId()]->getExternalAccountId());
        self::assertSame(28434, $byId[(string) $checking->getId()]->getBalanceCents(), 'the balance the live session reads');
        self::assertSame('hash-savings', $byId[(string) $savings->getId()]->getExternalKey(), 'the account kept learns its identification');
    }
}
