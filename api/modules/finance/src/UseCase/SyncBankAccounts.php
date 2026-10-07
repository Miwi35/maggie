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
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
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
    /** Enough for a busy month; a guard against paging forever, not a target. */
    private const MAX_PAGES_PER_ACCOUNT = 10;

    /** How far back a first sync reaches when nothing has been fetched yet. */
    private const FIRST_SYNC_DAYS = 90;

    /** Re-reading the last few days catches movements the bank settled late. */
    private const OVERLAP_DAYS = 3;

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly ImportStatement $importStatement,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
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

        foreach ($this->connectionRepository->findByUser($user) as $connection) {
            if (!$connection->isUsable()) {
                $results[] = [
                    'bankName' => $connection->getBankName(),
                    'status' => 'needs_reconnecting',
                    'message' => "L'accès à cette banque a expiré.",
                ];
                continue;
            }

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
                    $results[] = [
                        'bankName' => $connection->getBankName(),
                        'accountName' => $account->getName(),
                        'status' => 'failed',
                        'message' => $e->getMessage(),
                    ];
                    continue;
                }

                $imported += $outcome['imported'];
                $skipped += $outcome['skipped'];
                $calls += $outcome['calls'];
                $results[] = $outcome + [
                    'bankName' => $connection->getBankName(),
                    'accountName' => $account->getName(),
                    'status' => 'synced',
                ];
            }

            if (!$dryRun) {
                $connection->setLastSyncedAt(new \DateTimeImmutable());
            }
        }

        if (!$dryRun) {
            $this->em->flush();
        }

        return [
            'imported' => $imported,
            'skipped' => $skipped,
            'providerCalls' => $calls,
            'accounts' => $results,
        ];
    }

    /**
     * @param array<string, string> $psuHeaders
     *
     * @return array{imported: int, skipped: int, categorized: int, counterpartiesCompleted: int, pages: int, calls: int, from: string, balanceCents: ?int}
     */
    private function syncAccount(
        BankConnection $connection,
        Account $account,
        bool $dryRun,
        array $psuHeaders,
    ): array {
        $from = $this->windowStart($connection);
        $rows = [];
        $continuationKey = null;
        $pages = 0;

        do {
            $page = $this->client->listTransactions(
                (string) $account->getExternalAccountId(),
                $from,
                null,
                $continuationKey,
                $psuHeaders,
            );
            ++$pages;

            foreach ($page['transactions'] ?? [] as $remote) {
                $row = $this->toRow($remote, $account->getCurrency());
                if (null !== $row) {
                    $rows[] = $row;
                }
            }

            $continuationKey = $page['continuation_key'] ?? null;
        } while (null !== $continuationKey && $pages < self::MAX_PAGES_PER_ACCOUNT);

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
            'pages' => $pages,
            'calls' => $pages + 1,
            'from' => $from->format('Y-m-d'),
            'balanceCents' => $balanceCents,
        ];
    }

    /**
     * Ask for what is missing, not for everything: a first sync reaches back
     * three months, later ones only since the last, with a few days of overlap
     * for movements the bank settles late.
     */
    private function windowStart(BankConnection $connection): \DateTimeImmutable
    {
        $lastSynced = $connection->getLastSyncedAt();

        if (null === $lastSynced) {
            return (new \DateTimeImmutable())->modify(sprintf('-%d days', self::FIRST_SYNC_DAYS));
        }

        return $lastSynced->modify(sprintf('-%d days', self::OVERLAP_DAYS));
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
            static fn (Account $account) => $account->getBankConnection()?->getId()?->equals($connection->getId())
                && null !== $account->getExternalAccountId(),
        ));
    }

    /** @param array<string, mixed> $remote */
    private function toRow(array $remote, string $fallbackCurrency): ?StatementRow
    {
        $amount = $remote['transaction_amount']['amount'] ?? null;
        $currency = $remote['transaction_amount']['currency'] ?? $fallbackCurrency;
        $date = $remote['booking_date'] ?? $remote['value_date'] ?? $remote['transaction_date'] ?? null;

        if (null === $amount || !\is_string($date)) {
            return null;
        }

        $cents = (int) round(((float) $amount) * 100);

        // Credit or debit lives in its own field: the amount itself is unsigned.
        if (($remote['credit_debit_indicator'] ?? 'DBIT') === 'DBIT') {
            $cents = -abs($cents);
        } else {
            $cents = abs($cents);
        }

        try {
            $bookedAt = (new \DateTimeImmutable($date))->setTime(0, 0);
        } catch (\Exception) {
            return null;
        }

        // The other party, not the account holder: a debit names its creditor,
        // a credit its debtor.
        $counterparty = $this->readName($remote[$cents < 0 ? 'creditor' : 'debtor'] ?? null);
        $label = $this->readLabel($remote, $counterparty);
        $previousLabel = $this->readPreviousLabel($remote);

        return new StatementRow(
            bookedAt: $bookedAt,
            label: $label,
            amountCents: $cents,
            currency: \is_string($currency) ? $currency : $fallbackCurrency,
            lineNumber: 0,
            counterpartyName: $counterparty,
            knownAs: $previousLabel === $label ? [] : [$previousLabel],
        );
    }

    private function readName(mixed $party): ?string
    {
        $name = \is_array($party) ? ($party['name'] ?? null) : null;

        return \is_string($name) && '' !== trim($name) ? $this->squash($name) : null;
    }

    private function squash(string $value): string
    {
        return trim(preg_replace('/\s+/u', ' ', $value) ?? $value);
    }

    /**
     * What the movement says about itself. The counterparty only stands in for
     * it when the bank gave nothing else: it lives in its own field now, and
     * the label is what the bank rewrites every month.
     *
     * @param array<string, mixed> $remote
     */
    private function readLabel(array $remote, ?string $counterparty): string
    {
        return $this->firstFilled([
            $this->readRemittance($remote),
            $counterparty,
            $remote['creditor']['name'] ?? null,
            $remote['debtor']['name'] ?? null,
            $remote['merchant_category_code'] ?? null,
        ]);
    }

    /**
     * The label syncs wrote before the counterparty had a field of its own:
     * the party's name first, the remittance only after.
     *
     * @param array<string, mixed> $remote
     */
    private function readPreviousLabel(array $remote): string
    {
        return $this->firstFilled([
            $remote['creditor']['name'] ?? null,
            $remote['debtor']['name'] ?? null,
            $this->readRemittance($remote),
            $remote['merchant_category_code'] ?? null,
        ]);
    }

    /** @param list<mixed> $candidates */
    private function firstFilled(array $candidates): string
    {
        foreach ($candidates as $candidate) {
            if (\is_string($candidate) && '' !== trim($candidate)) {
                return $this->squash($candidate);
            }
        }

        return 'Sans libellé';
    }

    /** @param array<string, mixed> $remote */
    private function readRemittance(array $remote): ?string
    {
        $remittance = $remote['remittance_information'] ?? null;

        return \is_array($remittance) ? implode(' ', array_filter($remittance, 'is_string')) : (\is_string($remittance) ? $remittance : null);
    }
}
