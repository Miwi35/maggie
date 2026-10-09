<?php

namespace Maggie\Finance\Tests\Bank;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\ImportStatement;
use Maggie\Finance\UseCase\MergeDuplicateAccounts;
use Maggie\Finance\UseCase\SyncBankAccounts;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * A sync spends the bank's daily allowance, so what matters as much as the
 * movements it brings back is how few calls it makes and when it gives up.
 */
class SyncBankAccountsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
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

    private function sync(MockHttpClient $http): SyncBankAccounts
    {
        $container = self::getContainer();

        return new SyncBankAccounts(
            new EnableBankingClient($http, 'app-id', $this->keyPath, 'https://api.example.test'),
            $container->get(BankConnectionRepository::class),
            $container->get(AccountRepository::class),
            $container->get(ImportStatement::class),
            $container->get('doctrine.orm.entity_manager'),
            $container->get('messenger.default_bus'),
            $container->get(MergeDuplicateAccounts::class),
        );
    }

    /** A connected account, the way the authorization journey leaves things. */
    private function connectAccount(?\DateTimeImmutable $lastSynced = null): BankConnection
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $connection = new BankConnection();
        $connection->setUser($this->getFixture('test_user'));
        $connection->setBankName('N26');
        $connection->activate('session-1', new \DateTimeImmutable('+60 days'));
        $connection->setLastSyncedAt($lastSynced);
        $em->persist($connection);

        $account = $this->getFixture('checking');
        $account->setExternalAccountId('remote-1');
        $account->setBankConnection($connection);

        $em->flush();

        return $connection;
    }

    /**
     * A provider answering both endpoints a sync touches: the movements, and
     * the balance asked for right after.
     *
     * @param callable|list<string> $pages
     */
    private function provider(callable|array $pages, string $balance = '1250.00'): MockHttpClient
    {
        $next = 0;

        return new MockHttpClient(
            function (string $method, string $url, array $options) use ($pages, $balance, &$next) {
                if (str_contains($url, '/balances')) {
                    return new MockResponse(json_encode([
                        'balances' => [[
                            'balance_type' => 'CLBD',
                            'balance_amount' => ['amount' => $balance, 'currency' => 'EUR'],
                        ]],
                    ]));
                }

                if (\is_callable($pages)) {
                    return $pages($method, $url, $options);
                }

                return new MockResponse($pages[min($next++, \count($pages) - 1)]);
            },
        );
    }

    /** @param array<int, array<string, mixed>> $transactions */
    private function page(array $transactions, ?string $continuationKey = null): string
    {
        return json_encode(array_filter([
            'transactions' => $transactions,
            'continuation_key' => $continuationKey,
        ], static fn ($value) => null !== $value), JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function movement(string $date, string $amount, string $indicator, string $payee): array
    {
        return [
            'booking_date' => $date,
            'transaction_amount' => ['amount' => $amount, 'currency' => 'EUR'],
            'credit_debit_indicator' => $indicator,
            'creditor' => ['name' => $payee],
        ];
    }

    public function testItBringsMovementsInAndSignsThemTheWayTheBankMeant(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR MARKET'),
            $this->movement('2026-09-05', '3500.00', 'CRDT', 'SALAIRE'),
        ])]);

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(2, $result['imported']);
        // One page of movements, plus the balance.
        self::assertSame(2, $result['providerCalls']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $spend = $em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR MARKET']);
        $income = $em->getRepository(Transaction::class)->findOneBy(['label' => 'SALAIRE']);

        // The amount arrives unsigned: the indicator is what makes it a debit.
        self::assertSame(-4599, $spend->getAmountCents());
        self::assertSame(350000, $income->getAmountCents());
    }

    public function testWhatItWritesIsIndexedOrTheListsWouldNotShowIt(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR'),
        ])]);

        $this->sync($http)->execute($this->getFixture('test_user'));

        // A sync writes straight to the database, past the bus that normally
        // indexes: without this the movements exist and no list shows them.
        $this->assertElasticsearchIndexDispatched(Transaction::class);
        $this->assertElasticsearchIndexDispatched(Account::class);
    }

    public function testSyncingTwiceDoesNotDuplicateWhatIsAlreadyThere(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([$this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR')])]);

        $sync = $this->sync($http);
        $sync->execute($this->getFixture('test_user'));
        $second = $sync->execute($this->getFixture('test_user'));

        self::assertSame(0, $second['imported']);
        self::assertSame(1, $second['skipped']);
    }

    /**
     * @param array<string, mixed> $extra
     *
     * @return array<string, mixed>
     */
    private function bankLine(string $date, string $amount, string $indicator, array $extra): array
    {
        return [
            'booking_date' => $date,
            'transaction_amount' => ['amount' => $amount, 'currency' => 'EUR'],
            'credit_debit_indicator' => $indicator,
        ] + $extra;
    }

    private function transactionLabelled(string $label): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Transaction::class)->findOneBy(['label' => $label])
            ?? self::fail(sprintf('no transaction labelled "%s"', $label));
    }

    public function testADebitKeepsItsCreditorApartFromALabelTheBankRewritesEveryMonth(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-08-12', '13.49', 'DBIT', [
                'creditor' => ['name' => 'NETFLIX INTERNATIONAL'],
                'remittance_information' => ['PRLV SEPA NETFLIX 12/08 REF 884'],
            ]),
            $this->bankLine('2026-09-12', '13.49', 'DBIT', [
                'creditor' => ['name' => 'NETFLIX INTERNATIONAL'],
                'remittance_information' => ['PRLV SEPA NETFLIX 12/09 REF 991'],
            ]),
        ])]);

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(2, $result['imported']);

        $august = $this->transactionLabelled('PRLV SEPA NETFLIX 12/08 REF 884');
        $september = $this->transactionLabelled('PRLV SEPA NETFLIX 12/09 REF 991');

        // The label no longer swallows the creditor, and the key does not move.
        self::assertSame('NETFLIX INTERNATIONAL', $august->getCounterpartyName());
        self::assertSame('netflix international', $august->getCounterpartyKey());
        self::assertSame($august->getCounterpartyKey(), $september->getCounterpartyKey());

        $this->assertMercureUpdatePublished('/api/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testACreditTakesItsDebtorNotTheAccountHolderListedAsCreditor(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-09-28', '3500.00', 'CRDT', [
                'debtor' => ['name' => 'ACME SAS'],
                'creditor' => ['name' => 'MEVEN'],
                'remittance_information' => ['SALAIRE SEPTEMBRE'],
            ]),
        ])]);

        $this->sync($http)->execute($this->getFixture('test_user'));

        $salary = $this->transactionLabelled('SALAIRE SEPTEMBRE');
        self::assertSame('ACME SAS', $salary->getCounterpartyName());
        self::assertSame('acme sas', $salary->getCounterpartyKey());
    }

    public function testAnEmptyRemittanceKeepsTheCounterpartyAsLabel(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-09-02', '8.00', 'DBIT', [
                'creditor' => ['name' => 'BOULANGERIE DU COIN'],
                'remittance_information' => ['  '],
            ]),
        ])]);

        $this->sync($http)->execute($this->getFixture('test_user'));

        $bakery = $this->transactionLabelled('BOULANGERIE DU COIN');
        self::assertSame('BOULANGERIE DU COIN', $bakery->getCounterpartyName());
    }

    public function testALineWithNoPartyFallsBackOnItsLabelForTheCounterparty(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-09-02', '9.90', 'DBIT', [
                'remittance_information' => ['PRLV SEPA SPOTIFY 02/09'],
            ]),
        ])]);

        $this->sync($http)->execute($this->getFixture('test_user'));

        $line = $this->transactionLabelled('PRLV SEPA SPOTIFY 02/09');
        self::assertSame('PRLV SEPA SPOTIFY', $line->getCounterpartyName());
    }

    public function testTheNextSyncCompletesALineStoredBeforeTheCounterpartyExistedWithoutDuplicatingIt(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        // Stored by an earlier sync: the creditor had been folded into the label.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $legacy = (new Transaction())
            ->setUser($this->getFixture('test_user'))
            ->setAccount($this->getFixture('checking'))
            ->setLabel('NETFLIX INTERNATIONAL')
            ->setAmountCents(-1349)
            ->setBookedAt(new \DateTimeImmutable('2026-09-12'));
        $em->persist($legacy);
        $em->flush();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-09-12', '13.49', 'DBIT', [
                'creditor' => ['name' => 'NETFLIX INTERNATIONAL'],
                'remittance_information' => ['PRLV SEPA NETFLIX 12/09 REF 991'],
            ]),
        ])]);

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(0, $result['imported'], 'the same movement under its new label is not a new one');
        self::assertSame(1, $result['skipped']);
        self::assertSame(1, $result['accounts'][0]['counterpartiesCompleted']);

        $em->clear();
        $stored = $em->getRepository(Transaction::class)->findAll();
        self::assertCount(1, $stored);
        self::assertSame('NETFLIX INTERNATIONAL', $stored[0]->getCounterpartyName());
        self::assertSame('netflix international', $stored[0]->getCounterpartyKey());

        $this->assertMercureUpdatePublished('/api/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        // A third pass has nothing left to complete.
        $third = $this->sync($http)->execute($this->getFixture('test_user'));
        self::assertSame(0, $third['accounts'][0]['counterpartiesCompleted']);
    }

    public function testARehearsalDoesNotCompleteStoredLines(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist((new Transaction())
            ->setUser($this->getFixture('test_user'))
            ->setAccount($this->getFixture('checking'))
            ->setLabel('NETFLIX INTERNATIONAL')
            ->setAmountCents(-1349)
            ->setBookedAt(new \DateTimeImmutable('2026-09-12')));
        $em->flush();

        $http = $this->provider([$this->page([
            $this->bankLine('2026-09-12', '13.49', 'DBIT', ['creditor' => ['name' => 'NETFLIX INTERNATIONAL']]),
        ])]);

        $this->sync($http)->execute($this->getFixture('test_user'), dryRun: true);

        $em->clear();
        self::assertNull($em->getRepository(Transaction::class)->findAll()[0]->getCounterpartyName());
    }

    public function testAFirstSyncReachesBackThreeMonthsAndLaterOnesOnlySinceTheLast(): void
    {
        $this->loadFixtures('account.yaml');
        $urls = [];

        $http = $this->provider(function (string $method, string $url) use (&$urls) {
            $urls[] = $url;

            return new MockResponse($this->page([]));
        });

        $this->connectAccount();
        $this->sync($http)->execute($this->getFixture('test_user'));

        $firstFrom = (new \DateTimeImmutable('-90 days'))->format('Y-m-d');
        self::assertStringContainsString('date_from='.$firstFrom, $urls[0]);

        // Once synced, only the days since — with a few of overlap for
        // movements the bank settles late.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $connection = $em->getRepository(BankConnection::class)->findAll()[0];
        $connection->setLastSyncedAt(new \DateTimeImmutable('2026-09-10'));
        $em->flush();

        $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertStringContainsString('date_from=2026-09-07', $urls[1]);
    }

    public function testItStopsPagingRatherThanFollowKeysForever(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        // A provider that always hands back another key would page for ever.
        $http = $this->provider(fn () => new MockResponse(
            $this->page([$this->movement('2026-09-02', '1.00', 'DBIT', 'X')], 'next-page'),
        ));

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(10, $result['accounts'][0]['pages']);
    }

    public function testARefusalStopsTheSyncInsteadOfBurningWhatIsLeft(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $calls = 0;
        $http = new MockHttpClient(function () use (&$calls) {
            ++$calls;

            return new MockResponse(json_encode(['error' => 'ASPSP_RATE_LIMIT_EXCEEDED']), ['http_code' => 429]);
        });

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(1, $calls, 'a refusal should not be retried');
        self::assertSame('rate_limited', $result['accounts'][0]['status']);
        self::assertStringContainsString('four a day', $result['accounts'][0]['message']);
    }

    public function testAnExpiredConsentIsReportedRatherThanCalled(): void
    {
        $this->loadFixtures('account.yaml');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $connection = $this->connectAccount();
        $connection->setConsentExpiresAt(new \DateTimeImmutable('-1 day'));
        $em->flush();

        $calls = 0;
        $http = new MockHttpClient(function () use (&$calls) {
            ++$calls;

            return new MockResponse($this->page([]));
        });

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        // Nothing is asked of a bank that will refuse anyway.
        self::assertSame(0, $calls);
        self::assertSame('needs_reconnecting', $result['accounts'][0]['status']);
    }

    public function testAUserTriggeredSyncSaysSoToEscapeTheDailyCeiling(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $headers = null;
        $http = $this->provider(function (string $method, string $url, array $options) use (&$headers) {
            $headers = $options['headers'];

            return new MockResponse($this->page([]));
        });

        $this->sync($http)->execute(
            $this->getFixture('test_user'),
            dryRun: false,
            psuHeaders: ['psu-ip-address' => '203.0.113.7'],
        );

        self::assertContains('psu-ip-address: 203.0.113.7', $headers);
    }

    public function testARehearsalReadsWithoutWriting(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR'),
        ])]);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $before = \count($em->getRepository(Transaction::class)->findAll());

        $result = $this->sync($http)->execute($this->getFixture('test_user'), dryRun: true);

        self::assertSame(1, $result['imported']);
        self::assertCount($before, $em->getRepository(Transaction::class)->findAll());
    }

    public function testItStoresTheBalanceTheBankReportsRatherThanSummingWhatWeHold(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $http = $this->provider([$this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR'),
        ])], balance: '842.13');

        $this->sync($http)->execute($this->getFixture('test_user'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $account = $em->find(Account::class, $this->getFixture('checking')->getId());

        // A synced window is months, not the whole life of the account: only
        // the bank knows what it actually holds.
        self::assertSame(84213, $account->getBalanceCents());
    }

    public function testARehearsalLeavesTheStoredBalanceAlone(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();
        $before = $this->getFixture('checking')->getBalanceCents();

        $http = $this->provider([$this->page([])], balance: '1.00');
        $this->sync($http)->execute($this->getFixture('test_user'), dryRun: true);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame($before, $em->find(Account::class, $this->getFixture('checking')->getId())->getBalanceCents());
    }

    public function testAnAccountNeverConnectedIsLeftAlone(): void
    {
        $this->loadFixtures('account.yaml');

        $calls = 0;
        $http = new MockHttpClient(function () use (&$calls) {
            ++$calls;

            return new MockResponse($this->page([]));
        });

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(0, $calls);
        self::assertSame([], $result['accounts']);
    }

    public function testAnActiveConnectionRecordsWhenItWasLastSynced(): void
    {
        $this->loadFixtures('account.yaml');
        $connection = $this->connectAccount();

        $http = $this->provider([$this->page([])]);
        $this->sync($http)->execute($this->getFixture('test_user'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(BankConnection::class, $connection->getId());

        self::assertNotNull($refreshed->getLastSyncedAt());
        self::assertSame(BankConnectionStatus::Active, $refreshed->getStatus());
    }
}
