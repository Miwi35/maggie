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
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\CompleteBankAuthorization;
use Maggie\Finance\UseCase\ImportStatement;
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
     */
    private function bank(array $accountsBySession, string &$session): MockHttpClient
    {
        return new MockHttpClient(function (string $method, string $url) use ($accountsBySession, &$session) {
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
                    'transactions' => [
                        [
                            'entry_reference' => 'bank-tx-edf',
                            'booking_date' => '2026-10-05',
                            'transaction_amount' => ['amount' => '206.00', 'currency' => 'EUR'],
                            'credit_debit_indicator' => 'DBIT',
                            'remittance_information' => ['PRELEVEMENT ELECTRICITE DE FRANCE'],
                        ],
                        [
                            'entry_reference' => 'bank-tx-salary',
                            'booking_date' => '2026-10-01',
                            'transaction_amount' => ['amount' => '2350.00', 'currency' => 'EUR'],
                            'credit_debit_indicator' => 'CRDT',
                            'remittance_information' => ['VIREMENT SALAIRE'],
                        ],
                    ],
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
        ))->execute($this->user());
    }

    /** The owner, read again: the assertions clear the entity manager between steps. */
    private function user(): User
    {
        return self::getContainer()->get('doctrine.orm.entity_manager')->find(User::class, $this->getFixture('test_user')->getId());
    }

    /** @return array<string, mixed> */
    private function remoteAccount(string $uid): array
    {
        return [
            'uid' => $uid,
            'identification_hash' => 'hash-of-the-current-account',
            'account_id' => ['iban' => self::IBAN],
            'name' => 'M. CADARE MEVEN',
            'currency' => 'EUR',
        ];
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
}
