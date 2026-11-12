<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Bank\EnableBanking\RateLimitedException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Event\BankSyncCompleted;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Pulls movements from the connected banks into the accounts they belong to.
 *
 * Deliberately frugal: banks cap unattended fetches — four a day is the common
 * figure — and every call is one of them. So a sync asks for the window it
 * actually needs, walks a bounded number of pages, and stops at the first
 * refusal instead of retrying into the ceiling.
 */
class SyncBankAccounts
{
    /** How far back a first sync reaches when nothing has been fetched yet. */
    private const FIRST_SYNC_DAYS = 90;

    /** Re-reading the last few days catches movements the bank settled late. */
    private const OVERLAP_DAYS = 3;

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly ReadBankStatement $readBankStatement,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly ImportStatement $importStatement,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly MergeDuplicateAccounts $mergeDuplicateAccounts,
        private readonly LoggerInterface $logger,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    /**
     * @param array<string, string> $psuHeaders sent when a person is waiting on
     *                                          the answer: banks exempt those
     *                                          calls from the daily ceiling
     *
     * @return array<string, mixed>
     */
    public function execute(User $user, bool $dryRun = false, array $psuHeaders = []): array
    {
        $results = [];
        $imported = 0;
        $skipped = 0;
        $calls = 0;
        $read = [];
        $stored = [];

        foreach ($this->connectionRepository->findByUser($user) as $connection) {
            if (!$connection->isUsable()) {
                $results[] = [
                    'bankName' => $connection->getBankName(),
                    'status' => 'needs_reconnecting',
                    'message' => "L'accès à cette banque a expiré.",
                ];
                continue;
            }

            $allRead = true;
            foreach ($this->accountsOf($connection) as $account) {
                try {
                    $outcome = $this->syncAccount($connection, $account, $dryRun, $psuHeaders);
                } catch (RateLimitedException $e) {
                    // One refusal means the day's allowance is spent for this
                    // bank: stop here rather than spend the rest on failures.
                    $results[] = [
                        'bankName' => $connection->getBankName(),
                        'accountName' => $account->getName(),
                        'status' => 'rate_limited',
                        'message' => $e->getMessage(),
                    ];
                    break 2;
                } catch (\RuntimeException $e) {
                    $allRead = false;
                    $results[] = [
                        'bankName' => $connection->getBankName(),
                        'accountName' => $account->getName(),
                        'status' => 'failed',
                        'message' => $e->getMessage(),
                    ];
                    continue;
                }

                $read[(string) $account->getId()] = true;
                $imported += $outcome['imported'];
                $skipped += $outcome['skipped'];
                $calls += $outcome['calls'];
                array_push($stored, ...$outcome['transactionIds']);
                unset($outcome['transactionIds']);
                $results[] = $outcome + [
                    'bankName' => $connection->getBankName(),
                    'accountName' => $account->getName(),
                    'status' => 'synced',
                ];
            }

            // Only when every account was read: the days of a failed one must
            // be asked for again next time.
            if (!$dryRun && $allRead) {
                $connection->setLastSyncedAt(new \DateTimeImmutable());
            }
        }

        if (!$dryRun) {
            $this->em->flush();

            $this->foldCopies($user, $read);
            $this->announce($user, $stored);
        }

        return [
            'imported' => $imported,
            'skipped' => $skipped,
            'providerCalls' => $calls,
            'accounts' => $results,
        ];
    }

    /**
     * A consent renewed while copies already existed cannot tell them apart
     * and brings one more (MAG-351): once its movements are in, they show
     * which account it is, and the copies fold back into one. The movements
     * are stored by then: a merge that fails does not fail the sync.
     *
     * @param array<string, true> $read the accounts this sync read
     */
    private function foldCopies(User $user, array $read): void
    {
        try {
            $result = $this->mergeDuplicateAccounts->execute(false, $user, $read);
        } catch (\Throwable $e) {
            $this->logger->error('Bank account copies not merged: {message}', ['message' => $e->getMessage(), 'exception' => $e]);

            return;
        }

        foreach ($result['groups'] as $group) {
            $this->logger->info('Bank account copies merged into {survivor}', $group);
        }
        if ([] !== $result['ambiguous']) {
            $this->logger->warning('Bank accounts left unmerged, they could be two real accounts', ['accounts' => $result['ambiguous']]);
        }
    }

    /**
     * The movements are stored and filed by now: what reacts to them — the
     * envelope alert first — does it on the event. A reaction that fails
     * does not fail the sync.
     *
     * @param list<string> $transactionIds the movements this sync stored
     */
    private function announce(User $user, array $transactionIds): void
    {
        try {
            $this->eventBus->dispatch(new BankSyncCompleted((string) $user->getId(), $transactionIds));
        } catch (\Throwable $e) {
            $this->logger->error('Reactions to a bank sync failed: {message}', ['message' => $e->getMessage(), 'exception' => $e]);
        }
    }

    /**
     * @param array<string, string> $psuHeaders
     *
     * @return array{imported: int, skipped: int, categorized: int, counterpartiesCompleted: int, ignored: int, received: int, pages: int, calls: int, from: string, balanceCents: ?int, transactionIds: list<string>}
     */
    private function syncAccount(
        BankConnection $connection,
        Account $account,
        bool $dryRun,
        array $psuHeaders,
    ): array {
        $from = $this->windowStart($connection, $account);
        $read = $this->readBankStatement->fetch($account, $from, null, $psuHeaders);
        $rows = $read['rows'];
        $received = $read['received'];
        $pages = $read['pages'];
        $ignored = $read['ignored'];

        $result = $this->importStatement->execute($account, $rows, $dryRun);

        // The bank's own figure, asked for separately: a synced window covers
        // months, never the whole life of the account, so a balance summed
        // from what we hold would be short by everything that came before.
        $balanceCents = $this->readBalance($account, $psuHeaders);

        if (null !== $balanceCents && !$dryRun) {
            $account->setBalanceCents($balanceCents);
            $this->reindex($account);
        }

        return [
            'imported' => $result['imported'],
            'skipped' => $result['skipped'],
            'categorized' => $result['categorized'],
            'counterpartiesCompleted' => $result['counterpartiesCompleted'],
            'ignored' => $ignored,
            'received' => $received,
            'pages' => $pages,
            'calls' => $pages + 1,
            'from' => $from->format('Y-m-d'),
            'balanceCents' => $balanceCents,
            'transactionIds' => $result['transactionIds'],
        ] + (0 === $received ? [
            'status' => 'empty_from_bank',
            'message' => sprintf('La banque n\'a renvoyé aucun mouvement depuis le %s.', $from->format('d/m/Y')),
        ] : []);
    }

    /**
     * Ask for what is missing, not for everything: a first sync reaches back
     * three months, later ones only since the last, with a few days of overlap
     * for movements the bank settles late. An account linked since the last
     * sync — a renewed consent that could not recognise it — has never been
     * read: its first sync reaches back too, so its history shows which
     * account it is a copy of.
     */
    private function windowStart(BankConnection $connection, Account $account): \DateTimeImmutable
    {
        $lastSynced = $connection->getLastSyncedAt();

        if (null === $lastSynced || $account->getId()->getDateTime() > $lastSynced) {
            return (new \DateTimeImmutable())->modify(sprintf('-%d days', self::FIRST_SYNC_DAYS));
        }

        $floor = (new \DateTimeImmutable())->modify(sprintf('-%d days', self::FIRST_SYNC_DAYS));

        return max($lastSynced->modify(sprintf('-%d days', self::OVERLAP_DAYS)), $floor);
    }

    /**
     * The balance the bank reports, in cents.
     *
     * Banks publish several — booked, available, forward — under codes that
     * vary. Booked first: it is the figure the user recognises from their app.
     *
     * @param array<string, string> $psuHeaders
     */
    private function readBalance(Account $account, array $psuHeaders): ?int
    {
        $response = $this->client->getBalances((string) $account->getExternalAccountId(), $psuHeaders);
        $balances = array_values(array_filter($response['balances'] ?? [], 'is_array'));

        if ([] === $balances) {
            return null;
        }

        $byType = [];
        foreach ($balances as $balance) {
            $type = \is_string($balance['balance_type'] ?? null) ? $balance['balance_type'] : '';
            $byType[$type] = $balance;
        }

        foreach (['CLBD', 'ITBD', 'XPCD', 'ITAV', 'OTHR'] as $preferred) {
            if (isset($byType[$preferred])) {
                return $this->toCents($byType[$preferred]);
            }
        }

        return $this->toCents($balances[0]);
    }

    /** @param array<string, mixed> $balance */
    private function toCents(array $balance): ?int
    {
        $amount = $balance['balance_amount']['amount'] ?? null;

        if (!\is_string($amount) && !\is_int($amount) && !\is_float($amount)) {
            return null;
        }

        return (int) round(((float) $amount) * 100);
    }

    /**
     * A sync writes straight to the database, so nothing on the bus indexes
     * what it touched — and the lists that read Elasticsearch would keep
     * showing the account as it was.
     */
    private function reindex(Account $account): void
    {
        $this->bus->dispatch(new IndexDocumentCommand(
            entityClass: Account::class,
            entityId: (string) $account->getId(),
        ));
    }

    /** @return Account[] */
    private function accountsOf(BankConnection $connection): array
    {
        return array_values(array_filter(
            $this->accountRepository->findByUser($connection->getUser()),
            // A closed account is one the bank no longer lists: its uid would
            // only earn a refusal.
            static fn (Account $account) => $account->getBankConnection()?->getId()?->equals($connection->getId())
                && null !== $account->getExternalAccountId()
                && !$account->isClosed(),
        ));
    }
}
