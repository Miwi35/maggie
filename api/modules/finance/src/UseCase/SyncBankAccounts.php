<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Bank\EnableBanking\RateLimitedException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\BankConnection;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;

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
    ) {
    }

    /**
     * @param array<string, string> $psuHeaders Sent when a person is waiting on
     *                                          the answer: banks exempt those
     *                                          calls from the daily ceiling.
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
     * @return array{imported: int, skipped: int, categorized: int, calls: int, from: string}
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
        $calls = 0;

        do {
            $page = $this->client->listTransactions(
                (string) $account->getExternalAccountId(),
                $from,
                null,
                $continuationKey,
                $psuHeaders,
            );
            ++$calls;

            foreach ($page['transactions'] ?? [] as $remote) {
                $row = $this->toRow($remote, $account->getCurrency());
                if ($row !== null) {
                    $rows[] = $row;
                }
            }

            $continuationKey = $page['continuation_key'] ?? null;
        } while ($continuationKey !== null && $calls < self::MAX_PAGES_PER_ACCOUNT);

        $result = $this->importStatement->execute($account, $rows, $dryRun);

        return [
            'imported' => $result['imported'],
            'skipped' => $result['skipped'],
            'categorized' => $result['categorized'],
            'calls' => $calls,
            'from' => $from->format('Y-m-d'),
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

        if ($lastSynced === null) {
            return (new \DateTimeImmutable())->modify(sprintf('-%d days', self::FIRST_SYNC_DAYS));
        }

        return $lastSynced->modify(sprintf('-%d days', self::OVERLAP_DAYS));
    }

    /** @return Account[] */
    private function accountsOf(BankConnection $connection): array
    {
        return array_values(array_filter(
            $this->accountRepository->findByUser($connection->getUser()),
            static fn (Account $account) => $account->getBankConnection()?->getId()?->equals($connection->getId())
                && $account->getExternalAccountId() !== null,
        ));
    }

    /** @param array<string, mixed> $remote */
    private function toRow(array $remote, string $fallbackCurrency): ?StatementRow
    {
        $amount = $remote['transaction_amount']['amount'] ?? null;
        $currency = $remote['transaction_amount']['currency'] ?? $fallbackCurrency;
        $date = $remote['booking_date'] ?? $remote['value_date'] ?? $remote['transaction_date'] ?? null;

        if ($amount === null || !\is_string($date)) {
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

        return new StatementRow(
            bookedAt: $bookedAt,
            label: $this->readLabel($remote),
            amountCents: $cents,
            currency: \is_string($currency) ? $currency : $fallbackCurrency,
            lineNumber: 0,
        );
    }

    /** @param array<string, mixed> $remote */
    private function readLabel(array $remote): string
    {
        $candidates = [
            $remote['creditor']['name'] ?? null,
            $remote['debtor']['name'] ?? null,
            \is_array($remote['remittance_information'] ?? null)
                ? implode(' ', $remote['remittance_information'])
                : ($remote['remittance_information'] ?? null),
            $remote['merchant_category_code'] ?? null,
        ];

        foreach ($candidates as $candidate) {
            if (\is_string($candidate) && trim($candidate) !== '') {
                return trim(preg_replace('/\s+/u', ' ', $candidate) ?? $candidate);
            }
        }

        return 'Sans libellé';
    }
}
