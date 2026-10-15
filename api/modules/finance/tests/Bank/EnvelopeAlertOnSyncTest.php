<?php

namespace Maggie\Finance\Tests\Bank;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\EnvelopeAlert;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\AlertEnvelopeLimits;
use Maggie\Finance\UseCase\ImportStatement;
use Maggie\Finance\UseCase\MergeDuplicateAccounts;
use Maggie\Finance\UseCase\SyncBankAccounts;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * Maggie interrupts when a bank sync takes an envelope past its threshold
 * (MAG-49) — once per threshold and per period, and only for what a sync
 * brought.
 */
class EnvelopeAlertOnSyncTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    private string $keyPath;

    private \DateTimeImmutable $today;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->keyPath = tempnam(sys_get_temp_dir(), 'eb-key-');
        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        file_put_contents($this->keyPath, $pem);

        $this->today = new \DateTimeImmutable('today');
        $this->loadFixtures('envelope_alert.yaml');
        $this->connectAccount();
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
        parent::tearDown();
    }

    public function testASyncCrossingTheThresholdAlertsOnceThenAgainAtTheLimit(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        $this->spend('leisure', 21000);

        // 210 + 40 = 250 / 300: past 80 %.
        $this->syncMovements([$this->movement('sync-1', '40.00', 'CINEMA PATHE')]);

        self::assertSame([80], $this->alertThresholds($envelope));
        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        self::assertSame(NotificationType::Finance, $notifications[0]->getType());
        self::assertSame('Loisirs : 83 % du budget (250 / 300 €)', $notifications[0]->getTitle());
        self::assertSame('/finance/budgets', $notifications[0]->getRelatedEntityIri());
        $this->assertMercureUpdatePublished('/notifications/');

        // 250 + 20 = 270: still between the two thresholds.
        $this->resetMercure();
        $this->syncMovements([$this->movement('sync-2', '20.00', 'CINEMA UGC')]);
        self::assertCount(1, $this->notifications());
        $this->assertNothingPublishedOnNotifications();

        // 270 + 35 = 305: past 100 %.
        $this->syncMovements([$this->movement('sync-3', '35.00', 'CINEMA MK2')]);
        self::assertSame([80, 100], $this->alertThresholds($envelope));
        $notifications = $this->notifications();
        self::assertCount(2, $notifications);
        self::assertSame('Loisirs : 101 % du budget (305 / 300 €)', $notifications[1]->getTitle());
    }

    public function testASpendOnASubCategoryCountsInTheEnvelopeAbove(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        $this->spend('leisure', 21000);

        $this->syncMovements([$this->movement('sync-bar', '40.00', 'OBSYDIAN BAR')]);

        self::assertSame([80], $this->alertThresholds($envelope));
        self::assertCount(1, $this->notifications());
    }

    public function testTheThresholdSetOnTheEnvelopeIsTheOneThatAlerts(): void
    {
        $envelope = $this->envelope('leisure', 30000, 50);
        $this->spend('leisure', 12000);

        // 120 + 40 = 160 / 300: past 50 %, short of the default 80 %.
        $this->syncMovements([$this->movement('sync-1', '40.00', 'CINEMA PATHE')]);

        self::assertSame([50], $this->alertThresholds($envelope));
        self::assertSame('Loisirs : 53 % du budget (160 / 300 €)', $this->notifications()[0]->getTitle());
    }

    public function testOneSyncCrossingBothThresholdsSaysItOnce(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        $this->spend('leisure', 21000);

        $this->syncMovements([$this->movement('sync-1', '100.00', 'CINEMA PATHE')]);

        self::assertSame([80, 100], $this->alertThresholds($envelope));
        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        self::assertSame('Loisirs : 103 % du budget (310 / 300 €)', $notifications[0]->getTitle());
    }

    public function testAManualEntryCrossingTheThresholdAlertsNothing(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        $this->spend('leisure', 21000);

        // Through the same door as the app and the tools: the bus.
        self::getContainer()->get('messenger.default_bus')->dispatch(new CreateTransactionCommand(
            userId: (string) $this->user()->getId(),
            accountId: (string) $this->getFixture('checking')->getId(),
            amountCents: -4000,
            bookedAt: $this->today->format('Y-m-d'),
            label: 'Concert',
            categoryId: (string) $this->getFixture('leisure')->getId(),
        ));

        self::assertSame([], $this->alertThresholds($envelope));
        self::assertSame([], $this->notifications());

        // A sync that does not touch Loisirs says nothing either, though the
        // envelope is past 80 % now.
        $this->syncMovements([$this->movement('sync-other', '12.00', 'BOULANGERIE')]);
        self::assertSame([], $this->notifications());
    }

    public function testASyncAlreadyPastTheThresholdBeforehandStaysSilent(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        // Typed by hand: 85 % before any sync.
        $this->spend('leisure', 25500);

        $this->syncMovements([$this->movement('sync-1', '10.00', 'CINEMA PATHE')]);

        self::assertSame([], $this->alertThresholds($envelope));
        self::assertSame([], $this->notifications());
    }

    public function testTheNextMonthStartsAfresh(): void
    {
        $envelope = $this->envelope('leisure', 30000);
        $alerts = self::getContainer()->get(AlertEnvelopeLimits::class);

        // Last month's envelope already alerted at 80 %: this month's has its own count.
        $lastMonth = $this->today->modify('first day of last month');
        $previous = $this->envelope('leisure', 30000, null, $lastMonth);
        $this->em()->persist(new EnvelopeAlert($previous, EnvelopeAlert::periodOf($previous), 80));
        $this->em()->flush();

        $this->spend('leisure', 21000);
        $synced = $this->spend('leisure', 4000);

        self::assertSame(1, $alerts->execute($this->user(), [(string) $synced->getId()], $this->today));
        self::assertSame([80], $this->alertThresholds($envelope));
    }

    public function testMovementsOfAPastMonthDoNotAlert(): void
    {
        $lastMonth = $this->today->modify('first day of last month');
        $previous = $this->envelope('leisure', 30000, null, $lastMonth);
        $this->spend('leisure', 21000, $lastMonth);
        $synced = $this->spend('leisure', 4000, $lastMonth);

        $sent = self::getContainer()->get(AlertEnvelopeLimits::class)
            ->execute($this->user(), [(string) $synced->getId()], $this->today);

        self::assertSame(0, $sent);
        self::assertSame([], $this->alertThresholds($previous));
    }

    public function testADryRunAlertsNothing(): void
    {
        $this->envelope('leisure', 30000);
        $this->spend('leisure', 21000);

        $this->sync($this->provider([$this->page([$this->movement('sync-1', '40.00', 'CINEMA PATHE')])]))
            ->execute($this->user(), true);

        self::assertSame([], $this->notifications());
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
            $container->get('logger'),
            $container->get('event.bus'),
        );
    }

    /** @param list<array<string, mixed>> $movements */
    private function syncMovements(array $movements): void
    {
        $this->sync($this->provider([$this->page($movements)]))->execute($this->user());
    }

    private function connectAccount(): void
    {
        $connection = new BankConnection();
        $connection->setUser($this->getFixture('test_user'));
        $connection->setBankName('N26');
        $connection->activate('session-1', new \DateTimeImmutable('+60 days'));
        $this->em()->persist($connection);

        $account = $this->getFixture('checking');
        $account->setExternalAccountId('remote-1');
        $account->setBankConnection($connection);

        $this->em()->flush();
    }

    /** @param list<string> $pages */
    private function provider(array $pages): MockHttpClient
    {
        $next = 0;

        return new MockHttpClient(
            function (string $method, string $url) use ($pages, &$next) {
                if (str_contains($url, '/balances')) {
                    return new MockResponse(json_encode([
                        'balances' => [['balance_type' => 'CLBD', 'balance_amount' => ['amount' => '1000.00', 'currency' => 'EUR']]],
                    ]));
                }

                return new MockResponse($pages[min($next++, \count($pages) - 1)]);
            },
        );
    }

    /** @param list<array<string, mixed>> $transactions */
    private function page(array $transactions): string
    {
        return json_encode(['transactions' => $transactions], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function movement(string $reference, string $amount, string $payee): array
    {
        return [
            'entry_reference' => $reference,
            'booking_date' => $this->today->format('Y-m-d'),
            'transaction_amount' => ['amount' => $amount, 'currency' => 'EUR'],
            'credit_debit_indicator' => 'DBIT',
            'creditor' => ['name' => $payee],
        ];
    }

    private function envelope(string $category, int $amountCents, ?int $threshold = null, ?\DateTimeImmutable $month = null): Envelope
    {
        $month ??= $this->today;

        $envelope = (new Envelope())
            ->setUser($this->user())
            ->setCategory($this->category($category))
            ->setMode(BudgetMode::Monthly)
            ->setAmountCents($amountCents)
            ->setYear((int) $month->format('Y'))
            ->setMonth((int) $month->format('n'))
            ->setAlertThresholdPercent($threshold);
        $this->em()->persist($envelope);
        $this->em()->flush();

        return $envelope;
    }

    /** A spend already stored before the sync — typed, or synced earlier. */
    private function spend(string $category, int $cents, ?\DateTimeImmutable $on = null): Transaction
    {
        $transaction = (new Transaction())
            ->setUser($this->user())
            ->setAccount($this->getFixture('checking'))
            ->setCategory($this->category($category))
            ->setLabel('Déjà là')
            ->setAmountCents(-$cents)
            ->setCurrency('EUR')
            ->setBookedAt($on ?? $this->today)
            ->setStatus(TransactionStatus::Spent);
        $this->em()->persist($transaction);
        $this->em()->flush();

        return $transaction;
    }

    /** @return list<int> */
    private function alertThresholds(Envelope $envelope): array
    {
        $thresholds = array_map(
            static fn (EnvelopeAlert $alert) => $alert->getThresholdPercent(),
            $this->em()->getRepository(EnvelopeAlert::class)->findBy(['envelope' => $envelope->getId()]),
        );
        sort($thresholds);

        return $thresholds;
    }

    /** @return list<Notification> */
    private function notifications(): array
    {
        return $this->em()->getRepository(Notification::class)->findBy([], ['id' => 'ASC']);
    }

    private function assertNothingPublishedOnNotifications(): void
    {
        foreach ($this->getMercureHub()->getUpdates() as $update) {
            foreach ($update->getTopics() as $topic) {
                self::assertStringNotContainsString('/notifications/', $topic);
            }
        }
    }

    private function category(string $reference): Category
    {
        return $this->em()->find(Category::class, $this->getFixture($reference)->getId());
    }

    private function user(): User
    {
        return $this->em()->find(User::class, $this->getFixture('test_user')->getId());
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
