<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Bank\EnableBanking\BankRefusedException;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Bank\EnableBanking\RateLimitedException;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Specification\RealCurrency;
use Psr\Log\LoggerInterface;
use Symfony\Component\Uid\Ulid;

/**
 * Reads the movements of a connected account from the bank and turns them
 * into statement rows. Writes nothing: the sync stores what it reads, the
 * statement tools only look (MAG-802).
 */
class ReadBankStatement
{
    /** Enough for a busy month; a guard against paging forever, not a target. */
    private const MAX_PAGES_PER_ACCOUNT = 10;

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly AccountRepository $accountRepository,
        private readonly LoggerInterface $logger,
    ) {
    }

    /**
     * One account of the user, over a period they asked for.
     *
     * A bank that refuses the period (PSD2 often caps it at 90 days without a
     * fresh strong authentication) or its daily ceiling is an answer, not a
     * failure: `status` says which, and no row comes with it.
     *
     * @return array{account: Account, rows: list<StatementRow>, status: string, message: ?string, truncated: bool}
     *
     * @throws \InvalidArgumentException when the account is not one the user can read from the bank
     * @throws BankRefusedException      when the bank refuses for a reason that is not the period
     */
    public function read(User $user, string $accountId, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        $account = $this->readableAccount($user, $accountId);

        try {
            $read = $this->fetch($account, $from, $to);
        } catch (RateLimitedException $e) {
            return ['account' => $account, 'rows' => [], 'status' => 'rate_limited', 'message' => $e->getMessage(), 'truncated' => false];
        } catch (BankRefusedException $e) {
            if ($e->isAboutThePeriod()) {
                return ['account' => $account, 'rows' => [], 'status' => 'history_unavailable', 'message' => $e->getBankMessage(), 'truncated' => false];
            }

            throw $e;
        }

        return ['account' => $account, 'rows' => $read['rows'], 'status' => 'ok', 'message' => null, 'truncated' => $read['moreToRead']];
    }

    /**
     * Walks the pages of an account from a date, bounded.
     *
     * @param array<string, string> $psuHeaders sent when a person is waiting on
     *                                          the answer: banks exempt those
     *                                          calls from the daily ceiling
     *
     * @return array{rows: list<StatementRow>, received: int, ignored: int, pages: int, moreToRead: bool}
     */
    public function fetch(Account $account, \DateTimeImmutable $from, ?\DateTimeImmutable $to = null, array $psuHeaders = []): array
    {
        $rows = [];
        $continuationKey = null;
        $pages = 0;
        $withoutRealCurrency = 0;
        $ignored = 0;
        $unreadable = 0;
        $received = 0;
        $byStatus = [];

        do {
            $page = $this->client->listTransactions(
                (string) $account->getExternalAccountId(),
                $from,
                $to,
                $continuationKey,
                $psuHeaders,
            );
            ++$pages;

            foreach ($page['transactions'] ?? [] as $remote) {
                ++$received;
                $status = \is_string($remote['status'] ?? null) ? $remote['status'] : 'none';
                $byStatus[$status] = ($byStatus[$status] ?? 0) + 1;

                // A card authorisation for nothing (a subscription check, a
                // hold released at once) moves no money: it is no movement.
                if ($this->isZeroAmount($remote)) {
                    ++$ignored;
                    continue;
                }

                $currency = $remote['transaction_amount']['currency'] ?? null;
                if (null !== $currency && !RealCurrency::isReal($currency)) {
                    ++$withoutRealCurrency;
                }

                $row = $this->toRow($remote, $account->getCurrency());
                if (null !== $row) {
                    $rows[] = $row;
                } else {
                    ++$unreadable;
                }
            }

            $continuationKey = $page['continuation_key'] ?? null;
        } while (null !== $continuationKey && $pages < self::MAX_PAGES_PER_ACCOUNT);

        // What the bank sent and what became of it, counts only: when an
        // account looks emptier than it should, this tells whether the bank
        // sent too little or the sync dropped too much (MAG-439).
        $this->logger->info('Bank sync read {received} movement(s) in {pages} page(s) since {from}: {ignored} at zero, {unreadable} unreadable.', [
            'received' => $received,
            'pages' => $pages,
            'from' => $from->format('Y-m-d'),
            'ignored' => $ignored,
            'unreadable' => $unreadable,
            'byStatus' => $byStatus,
            'moreToRead' => null !== $continuationKey,
            'accountId' => (string) $account->getId(),
        ]);

        if ($withoutRealCurrency > 0) {
            $this->logger->warning('The bank gave no real currency for {count} movement(s): the account\'s own is used instead.', [
                'count' => $withoutRealCurrency,
                'accountId' => (string) $account->getId(),
                'accountCurrency' => $account->getCurrency(),
            ]);
        }

        return ['rows' => $rows, 'received' => $received, 'ignored' => $ignored, 'pages' => $pages, 'moreToRead' => null !== $continuationKey];
    }

    /**
     * The identifier a statement row goes by from one read to the next: the
     * bank's own reference, else the fingerprint the import deduplicates on.
     */
    public static function refOf(StatementRow $row): string
    {
        return $row->externalId ?? $row->fingerprint();
    }

    /**
     * @return array{0: \DateTimeImmutable, 1: \DateTimeImmutable}
     *
     * @throws \InvalidArgumentException on a malformed date or a period that ends before it starts
     */
    public static function parsePeriod(string $from, ?string $to): array
    {
        $start = self::parseDay($from, 'from');
        $end = null === $to || '' === $to ? new \DateTimeImmutable('today') : self::parseDay($to, 'to');

        if ($start > $end) {
            throw new \InvalidArgumentException('from must not be after to.');
        }

        return [$start, $end];
    }

    private static function parseDay(string $day, string $name): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day);
        if (false === $parsed || $parsed->format('Y-m-d') !== $day) {
            throw new \InvalidArgumentException(sprintf('%s must be a date as YYYY-MM-DD.', $name));
        }

        return $parsed;
    }

    private function readableAccount(User $user, string $accountId): Account
    {
        if (!Ulid::isValid($accountId)) {
            throw new \InvalidArgumentException('accountId is not a valid identifier.');
        }

        $wanted = Ulid::fromString($accountId);
        foreach ($this->accountRepository->findByUser($user) as $account) {
            if (!$account->getId()->equals($wanted)) {
                continue;
            }

            if (null === $account->getExternalAccountId() || $account->isClosed() || true !== $account->getBankConnection()?->isUsable()) {
                break;
            }

            return $account;
        }

        throw new \InvalidArgumentException('accountId is not one of your accounts linked to a usable bank connection.');
    }

    /** @param array<string, mixed> $remote */
    private function isZeroAmount(array $remote): bool
    {
        $amount = $remote['transaction_amount']['amount'] ?? null;

        return (\is_string($amount) || \is_int($amount) || \is_float($amount)) && is_numeric($amount) && 0.0 === (float) $amount;
    }

    /** @param array<string, mixed> $remote */
    private function toRow(array $remote, string $fallbackCurrency): ?StatementRow
    {
        $amount = $remote['transaction_amount']['amount'] ?? null;
        // « XXX » is « no currency »: the account's own is what the movement is in.
        $currency = RealCurrency::resolve($remote['transaction_amount']['currency'] ?? null, $fallbackCurrency);
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
            currency: $currency,
            lineNumber: 0,
            counterpartyName: $counterparty,
            knownAs: $previousLabel === $label ? [] : [$previousLabel],
            externalId: $this->readReference($remote),
        );
    }

    /**
     * The bank's reference for the movement: `entry_reference`, the one banks
     * keep from one read to the next. `transaction_id` is no stand-in — it is
     * a handle issued anew with each read, and would make every read new.
     *
     * @param array<string, mixed> $remote
     */
    private function readReference(array $remote): ?string
    {
        $value = $remote['entry_reference'] ?? null;

        return \is_string($value) && '' !== trim($value) ? trim($value) : null;
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
