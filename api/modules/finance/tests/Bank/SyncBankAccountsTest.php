<?php

namespace Maggie\Finance\Tests\Bank;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BankConnectionStatus;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\ImportStatement;
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

    private string $keyPath;

    protected function setUp(): void
    {
        self::bootKernel();

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

    /** @param array<int, array<string, mixed>> $transactions */
    private function page(array $transactions, ?string $continuationKey = null): string
    {
        return json_encode(array_filter([
            'transactions' => $transactions,
            'continuation_key' => $continuationKey,
        ], static fn ($value) => $value !== null), JSON_THROW_ON_ERROR);
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

        $http = new MockHttpClient([new MockResponse($this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR MARKET'),
            $this->movement('2026-09-05', '3500.00', 'CRDT', 'SALAIRE'),
        ]))]);

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(2, $result['imported']);
        self::assertSame(1, $result['providerCalls']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $spend = $em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR MARKET']);
        $income = $em->getRepository(Transaction::class)->findOneBy(['label' => 'SALAIRE']);

        // The amount arrives unsigned: the indicator is what makes it a debit.
        self::assertSame(-4599, $spend->getAmountCents());
        self::assertSame(350000, $income->getAmountCents());
    }

    public function testSyncingTwiceDoesNotDuplicateWhatIsAlreadyThere(): void
    {
        $this->loadFixtures('account.yaml');
        $this->connectAccount();

        $page = $this->page([$this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR')]);
        $http = new MockHttpClient([new MockResponse($page), new MockResponse($page)]);

        $sync = $this->sync($http);
        $sync->execute($this->getFixture('test_user'));
        $second = $sync->execute($this->getFixture('test_user'));

        self::assertSame(0, $second['imported']);
        self::assertSame(1, $second['skipped']);
    }

    public function testAFirstSyncReachesBackThreeMonthsAndLaterOnesOnlySinceTheLast(): void
    {
        $this->loadFixtures('account.yaml');
        $urls = [];

        $http = new MockHttpClient(function (string $method, string $url) use (&$urls) {
            $urls[] = $url;

            return new MockResponse($this->page([]));
        });

        $this->connectAccount();
        $this->sync($http)->execute($this->getFixture('test_user'));

        $firstFrom = (new \DateTimeImmutable('-90 days'))->format('Y-m-d');
        self::assertStringContainsString('date_from=' . $firstFrom, $urls[0]);

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
        $http = new MockHttpClient(fn () => new MockResponse(
            $this->page([$this->movement('2026-09-02', '1.00', 'DBIT', 'X')], 'next-page'),
        ));

        $result = $this->sync($http)->execute($this->getFixture('test_user'));

        self::assertSame(10, $result['providerCalls']);
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
        $http = new MockHttpClient(function (string $method, string $url, array $options) use (&$headers) {
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

        $http = new MockHttpClient([new MockResponse($this->page([
            $this->movement('2026-09-02', '45.99', 'DBIT', 'CARREFOUR'),
        ]))]);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $before = \count($em->getRepository(Transaction::class)->findAll());

        $result = $this->sync($http)->execute($this->getFixture('test_user'), dryRun: true);

        self::assertSame(1, $result['imported']);
        self::assertCount($before, $em->getRepository(Transaction::class)->findAll());
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

        $http = new MockHttpClient([new MockResponse($this->page([]))]);
        $this->sync($http)->execute($this->getFixture('test_user'));

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(BankConnection::class, $connection->getId());

        self::assertNotNull($refreshed->getLastSyncedAt());
        self::assertSame(BankConnectionStatus::Active, $refreshed->getStatus());
    }
}
