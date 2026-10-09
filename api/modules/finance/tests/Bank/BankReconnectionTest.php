<?php

namespace Maggie\Finance\Tests\Bank;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\CompleteBankAuthorization;
use Maggie\Finance\UseCase\ImportStatement;
use Maggie\Finance\UseCase\MergeDuplicateAccounts;
use Maggie\Finance\UseCase\StartBankAuthorization;
use Maggie\Finance\UseCase\SyncBankAccounts;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * A renewed consent is the same bank and the same accounts (MAG-351).
 *
 * Enable Banking hands out a new account `uid` with every session: what stays
 * is the account's identification — its IBAN, and the hash the provider
 * derives from it. Recognising an account by its `uid` alone made every
 * reconnection a second copy of every account, and every movement the next
 * sync read a second time.
 */
class BankReconnectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    private const IBAN = 'FR7630001007941234567890185';

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
     * A bank that answers the way Enable Banking does: each session names the
     * accounts with uids of its own, the movements and the balance are the same
     * whatever uid asks for them.
     *
     * @param array<string, list<array<string, mixed>>> $accountsBySession
     * @param array<string, string>                     $edfLabelBySession how the bank spells
     *                                                                     the EDF debit in each session
     * @param bool                                      $handlesOnly       a bank that gives no `entry_reference`,
     *                                                                     only a `transaction_id` of the session
     */
    private function bank(array $accountsBySession, string &$session, array $edfLabelBySession = [], bool $handlesOnly = false): MockHttpClient
    {
        return new MockHttpClient(function (string $method, string $url) use ($accountsBySession, &$session, $edfLabelBySession, $handlesOnly) {
            $path = (string) parse_url($url, PHP_URL_PATH);

            return match (true) {
                'POST' === $method && '/auth' === $path => new MockResponse(json_encode(['url' => 'https://bank.example/c'])),
                'POST' === $method && '/sessions' === $path => new MockResponse(json_encode([
                    'session_id' => $session,
                    'access' => ['valid_until' => (new \DateTimeImmutable('+90 days'))->format(DATE_ATOM)],
                    'accounts' => $accountsBySession[$session],
                ])),
                'GET' === $method && str_starts_with($path, '/sessions/') => new MockResponse(json_encode([
                    'accounts' => array_map(static fn (array $a) => $a['uid'], $accountsBySession[$session]),
                    'accounts_data' => array_map(
                        static fn (array $a) => ['uid' => $a['uid'], 'identification_hash' => $a['identification_hash'] ?? null],
                        $accountsBySession[$session],
                    ),
                ])),
                str_ends_with($path, '/balances') => new MockResponse(json_encode([
                    'balances' => [['balance_type' => 'CLBD', 'balance_amount' => ['amount' => '-201.67', 'currency' => 'EUR']]],
                ])),
                str_ends_with($path, '/transactions') => new MockResponse(json_encode([
                    'transactions' => array_map(static fn (array $movement) => $handlesOnly
                        ? ['transaction_id' => $movement['entry_reference'].'@'.$session] + array_diff_key($movement, ['entry_reference' => true])
                        : $movement, [
                            [
                                'entry_reference' => 'bank-tx-edf',
                                'booking_date' => '2026-10-05',
                                'transaction_amount' => ['amount' => '206.00', 'currency' => 'EUR'],
                                'credit_debit_indicator' => 'DBIT',
                                'remittance_information' => [$edfLabelBySession[$session] ?? 'PRELEVEMENT ELECTRICITE DE FRANCE'],
                            ],
                            [
                                'entry_reference' => 'bank-tx-salary',
                                'booking_date' => '2026-10-01',
                                'transaction_amount' => ['amount' => '2350.00', 'currency' => 'EUR'],
                                'credit_debit_indicator' => 'CRDT',
                                'remittance_information' => ['VIREMENT SALAIRE'],
                            ],
                        ]),
                ])),
                default => new MockResponse('{}', ['http_code' => 404]),
            };
        });
    }

    private function client(MockHttpClient $http): EnableBankingClient
    {
        return new EnableBankingClient($http, 'app-id', $this->keyPath, 'https://api.example.test');
    }

    private function connect(MockHttpClient $http): BankConnection
    {
        $container = self::getContainer();
        $connection = (new StartBankAuthorization(
            $this->client($http),
            $container->get('doctrine.orm.entity_manager'),
            $container->get(BankConnectionRepository::class),
            'https://maggieai.fr/api/finance/bank-callback',
        ))->execute($this->user(), 'Société Générale')['connection'];

        (new CompleteBankAuthorization(
            $this->client($http),
            $container->get(BankConnectionRepository::class),
            $container->get(AccountRepository::class),
            $container->get('doctrine.orm.entity_manager'),
            $container->get('messenger.default_bus'),
        ))->execute($connection->getState(), 'code');

        return $connection;
    }

    private function sync(MockHttpClient $http): void
    {
        $container = self::getContainer();

        (new SyncBankAccounts(
            $this->client($http),
            $container->get(BankConnectionRepository::class),
            $container->get(AccountRepository::class),
            $container->get(ImportStatement::class),
            $container->get('doctrine.orm.entity_manager'),
            $container->get('messenger.default_bus'),
            $container->get(MergeDuplicateAccounts::class),
        ))->execute($this->user());
    }

    /** The owner, read again: the assertions clear the entity manager between steps. */
    private function user(): User
    {
        return self::getContainer()->get('doctrine.orm.entity_manager')->find(User::class, $this->getFixture('test_user')->getId());
    }

    /** @return array<string, mixed> */
    private function remoteAccount(string $uid, string $hash = 'hash-of-the-current-account', string $iban = self::IBAN, string $name = 'M. CADARE MEVEN'): array
    {
        return [
            'uid' => $uid,
            'identification_hash' => $hash,
            'account_id' => ['iban' => $iban],
            'name' => $name,
            'currency' => 'EUR',
        ];
    }

    /** An account linked before identifications were kept: a uid, no key. */
    private function legacyAccount(BankConnection $connection, string $uid, string $name, int $balanceCents = 0): Account
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $account = (new Account())
            ->setUser($this->user())
            ->setName($name)
            ->setBank($connection->getBankName())
            ->setCurrency('EUR')
            ->setBalanceCents($balanceCents)
            ->setExternalAccountId($uid)
            ->setBankConnection($connection);
        $em->persist($account);
        $em->flush();

        return $account;
    }

    /** A movement an earlier sync stored, before bank references were kept. */
    private function storedMovement(Account $account, string $label, int $amountCents, string $day): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $em->persist((new Transaction())
            ->setUser($this->user())
            ->setAccount($account)
            ->setLabel($label)
            ->setAmountCents($amountCents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable($day))
            ->setStatus(TransactionStatus::Spent));
        $em->flush();
    }

    /** @return list<Account> */
    private function bankAccounts(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return array_values(array_filter(
            $em->getRepository(Account::class)->findAll(),
            static fn (Account $account) => null !== $account->getBankConnection(),
        ));
    }

    private function monthTotal(): int
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return (int) $em->createQuery(
            'SELECT COALESCE(SUM(t.amountCents), 0) FROM '.Transaction::class." t WHERE t.bookedAt >= '2026-10-01' AND t.bookedAt < '2026-11-01'",
        )->getSingleScalarResult();
    }

    public function testARenewedConsentWithNewUidsCreatesNoAccountAndNoMovement(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank([
            'session-1' => [$this->remoteAccount('uid-first-session')],
            'session-2' => [$this->remoteAccount('uid-second-session')],
        ], $session);

        $this->connect($http);
        $this->sync($http);

        $accountsBefore = $this->bankAccounts();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $transactionsBefore = \count($em->getRepository(Transaction::class)->findAll());
        $totalBefore = $this->monthTotal();

        self::assertCount(1, $accountsBefore);
        self::assertSame(2, $transactionsBefore);

        // The consent runs out, the owner renews it: same bank, same IBAN,
        // a new session and new uids.
        $session = 'session-2';
        $this->connect($http);
        $this->sync($http);

        $accountsAfter = $this->bankAccounts();
        self::assertCount(1, $accountsAfter, 'a renewed consent must not create a second copy of the account');
        self::assertTrue($accountsBefore[0]->getId()->equals($accountsAfter[0]->getId()));
        self::assertSame('uid-second-session', $accountsAfter[0]->getExternalAccountId(), 'the account follows the new session');

        self::assertCount($transactionsBefore, $em->getRepository(Transaction::class)->findAll(), 'the movements read again are recognised');
        self::assertSame($totalBefore, $this->monthTotal(), "the month's figures do not move with a reconnection");
    }

    public function testTheReconnectionIsPublishedAndReindexed(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank([
            'session-1' => [$this->remoteAccount('uid-first-session')],
            'session-2' => [$this->remoteAccount('uid-second-session')],
        ], $session);

        $this->connect($http);
        $this->sync($http);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $session = 'session-2';
        $this->connect($http);
        $account = $this->bankAccounts()[0];

        // The list of accounts reads Elasticsearch: the account it holds is
        // reindexed with its new uid, and no second one appears.
        $this->assertElasticsearchIndexDispatchedFor(Account::class, (string) $account->getId());
        self::assertSame([(string) $account->getId()], array_values(array_unique($this->reindexedIdsOf(Account::class))));
    }

    public function testAnAccountTheBankNoLongerListsIsClosedNotDeleted(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank([
            'session-1' => [$this->remoteAccount('uid-1'), $this->remoteAccount('uid-livret', 'hash-livret', 'FR7630001007940000000000001', 'Livret A')],
            'session-2' => [$this->remoteAccount('uid-2')],
            'session-3' => [$this->remoteAccount('uid-3'), $this->remoteAccount('uid-livret-3', 'hash-livret', 'FR7630001007940000000000001', 'Livret A')],
        ], $session);

        $this->connect($http);
        $this->sync($http);

        $session = 'session-2';
        $this->connect($http);

        $livret = array_values(array_filter($this->bankAccounts(), static fn (Account $a) => 'Livret A' === $a->getName()));
        self::assertCount(1, $livret, 'a closed account is kept, with its history');
        self::assertTrue($livret[0]->isClosed());

        // A closed account is not asked for: its uid would only earn a refusal.
        $asked = [];
        $session = 'session-2';
        $spy = new MockHttpClient(function (string $method, string $url, array $options) use ($http, &$asked) {
            $asked[] = (string) parse_url($url, PHP_URL_PATH);

            return $http->request($method, $url, $options);
        });
        $this->sync($spy);
        self::assertSame([], array_filter($asked, static fn (string $path) => str_contains($path, 'uid-livret')));

        // Listed again later: the same account, open again.
        $session = 'session-3';
        $this->connect($http);

        $accounts = $this->bankAccounts();
        self::assertCount(2, $accounts);
        $reopened = array_values(array_filter($accounts, static fn (Account $a) => 'Livret A' === $a->getName()))[0];
        self::assertTrue($reopened->getId()->equals($livret[0]->getId()));
        self::assertFalse($reopened->isClosed());
    }

    public function testAnAccountLinkedBeforeKeysWereKeptIsAdoptedByItsName(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank(['session-1' => [], 'session-2' => [$this->remoteAccount('uid-new')]], $session);
        $connection = $this->connect($http);
        $legacy = $this->legacyAccount($connection, 'uid-old', 'M. CADARE MEVEN');

        $session = 'session-2';
        $this->connect($http);

        $accounts = $this->bankAccounts();
        self::assertCount(1, $accounts);
        self::assertTrue($legacy->getId()->equals($accounts[0]->getId()));
        self::assertSame('hash-of-the-current-account', $accounts[0]->getExternalKey(), 'it learns its key on the way');
    }

    public function testTwoUnkeyedAccountsOfTheSameNameAreNotGuessedBetween(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank(['session-1' => [], 'session-2' => [$this->remoteAccount('uid-new', name: 'Meven Cadare')]], $session);
        $connection = $this->connect($http);
        $this->legacyAccount($connection, 'uid-a', 'Meven Cadare');
        $this->legacyAccount($connection, 'uid-b', 'Meven Cadare');

        $session = 'session-2';
        $this->connect($http);

        // Picking one would risk pouring one real account into another: the
        // new one stands apart, and the catch-up command merges what is a copy.
        $accounts = $this->bankAccounts();
        self::assertCount(3, $accounts);
        self::assertCount(1, array_filter($accounts, static fn (Account $a) => 'uid-new' === $a->getExternalAccountId()));
    }

    public function testReconnectingWithCopiesAlreadyThereLeavesOneAccountAndEachMovementOnce(): void
    {
        $this->loadFixtures('account.yaml');

        // Production on 9 Oct. (recette refused): two copies of one account
        // from earlier consents, neither with a key, the older one's balance
        // left behind by its dead session — and the catch-up never run.
        $session = 'session-1';
        $http = $this->bank(['session-1' => [], 'session-2' => [$this->remoteAccount('uid-new')]], $session);
        $connection = $this->connect($http);
        $original = $this->legacyAccount($connection, 'uid-a', 'M. CADARE MEVEN', -15000);
        $copy = $this->legacyAccount($connection, 'uid-b', 'M. CADARE MEVEN', -20167);
        $this->storedMovement($original, 'CARTE BOULANGERIE', -450, '2026-09-20');
        foreach ([$original, $copy] as $account) {
            $this->storedMovement($account, 'VIREMENT SALAIRE', 235000, '2026-10-01');
            $this->storedMovement($account, 'PRELEVEMENT ELECTRICITE DE FRANCE', -20600, '2026-10-05');
        }

        // The owner reconnects, then « Récupérer les opérations ».
        $session = 'session-2';
        $this->connect($http);
        $this->sync($http);

        $accounts = $this->bankAccounts();
        self::assertCount(1, $accounts, 'one real account, one Account, without anyone running a command');
        self::assertTrue($original->getId()->equals($accounts[0]->getId()), 'the oldest survives');
        self::assertSame('uid-new', $accounts[0]->getExternalAccountId());
        self::assertSame('hash-of-the-current-account', $accounts[0]->getExternalKey(), 'the next consent recognises it outright');
        self::assertFalse($accounts[0]->isClosed());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Transaction::class)->findAll(), 'each movement once');
        self::assertSame(235000 - 20600, $this->monthTotal());
    }

    public function testTheBankReferenceRecognisesAMovementWhoseLabelChanged(): void
    {
        $this->loadFixtures('account.yaml');

        $session = 'session-1';
        $http = $this->bank(
            ['session-1' => [$this->remoteAccount('uid-1')], 'session-2' => [$this->remoteAccount('uid-2')]],
            $session,
            ['session-2' => 'PRLV SEPA EDF CLIENTS PARTICULIERS'],
        );

        $this->connect($http);
        $this->sync($http);

        $session = 'session-2';
        $this->connect($http);
        $this->sync($http);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $stored = $em->getRepository(Transaction::class)->findBy(['externalId' => 'bank-tx-edf']);
        self::assertCount(1, $stored, 'one movement, whatever the bank calls it this time');
        self::assertSame(2, \count($em->getRepository(Transaction::class)->findAll()));
    }

    public function testATransactionIdThatChangesWithTheSessionIsNotTakenForAReference(): void
    {
        $this->loadFixtures('account.yaml');

        // `transaction_id` is a handle for fetching details, issued anew with
        // each read: only `entry_reference` says which movement it is.
        $session = 'session-1';
        $http = $this->bank(
            ['session-1' => [$this->remoteAccount('uid-1')], 'session-2' => [$this->remoteAccount('uid-2')]],
            $session,
            handlesOnly: true,
        );

        $this->connect($http);
        $this->sync($http);

        $session = 'session-2';
        $this->connect($http);
        $this->sync($http);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(Transaction::class)->findAll(), 'the second read adds nothing');
    }
}
