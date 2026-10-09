<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface as HttpClientException;

/**
 * Folds the copies of a bank account that renewed consents created back into
 * one (MAG-351), and drops the movements the copies held twice.
 *
 * Two accounts are the same real account when they carry the same
 * identification at the bank. Accounts linked before identifications were
 * kept have none: those are first given theirs from the live session, and
 * the ones left without — uids of expired sessions — are recognised by what
 * a copy shares with its original: the connection, the name, the currency,
 * and the balance or the movements. A lookalike that would fit two different
 * real accounts is left alone and reported.
 *
 * The oldest account survives: it holds the longest history, and the
 * references the owner made to it. Running it twice merges nothing more.
 * Every sync runs it for its owner, so no copy outlives the next one.
 */
class MergeDuplicateAccounts
{
    /** @var array<string, array<string, array<int, int>>> account id → its movements, for one run */
    private array $movementDays = [];

    public function __construct(
        private readonly EnableBankingClient $client,
        private readonly AccountRepository $accountRepository,
        private readonly BankConnectionRepository $connectionRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    /**
     * @param ?User $user         one owner's accounts — the sync folds its own
     *                            copies — or, given null, everyone's
     * @param bool  $readSessions ask the provider for the identifications the
     *                            stored keys lack; the sync does without, the
     *                            authorization having stored those of its session
     *
     * @return array{keysLearnt: int, groups: list<array{survivor: string, name: string, merged: list<string>, moved: int, dropped: int}>, ambiguous: list<string>, warnings: list<string>}
     */
    public function execute(bool $dryRun = true, ?User $user = null, bool $readSessions = true): array
    {
        $this->movementDays = [];
        $warnings = [];
        $keys = $this->learnKeys($user, $readSessions, $warnings);

        $accounts = array_values(array_filter(
            null === $user ? $this->accountRepository->findAll() : $this->accountRepository->findByUser($user),
            static fn (Account $account) => null !== $account->getBankConnection() || null !== $account->getExternalKey(),
        ));
        usort($accounts, static fn (Account $a, Account $b) => strcmp((string) $a->getId(), (string) $b->getId()));

        [$groups, $ambiguous] = $this->group($accounts, $keys);

        $report = [];
        $removed = [];
        foreach ($groups as $group) {
            $report[] = $this->merge($group, $keys, $dryRun);
            foreach (\array_slice($group, 1) as $copy) {
                $removed[(string) $copy->getId()] = true;
            }
        }

        // The accounts that stay learn their identification, so the next
        // consent recognises them without guessing.
        $learnt = array_values(array_filter(
            $accounts,
            static fn (Account $account) => null === $account->getExternalKey()
                && isset($keys[(string) $account->getId()])
                && !isset($removed[(string) $account->getId()]),
        ));

        if (!$dryRun && [] !== $learnt) {
            foreach ($learnt as $account) {
                $account->setExternalKey($keys[(string) $account->getId()]);
            }
            $this->em->flush();
        }

        return [
            'keysLearnt' => \count($learnt),
            'groups' => $report,
            'ambiguous' => array_map(static fn (Account $account) => sprintf('%s (%s)', $account->getName(), $account->getId()), $ambiguous),
            'warnings' => $warnings,
        ];
    }

    /**
     * Each account's identification: the stored one, else the one the live
     * session gives its uid. Read only — the sessions are Enable Banking's,
     * not the bank's, so asking costs none of the bank's daily allowance.
     *
     * @param list<string> $warnings
     *
     * @return array<string, string> account id → key
     */
    private function learnKeys(?User $user, bool $readSessions, array &$warnings): array
    {
        $byUid = [];

        foreach (null === $user ? $this->connectionRepository->findAll() : $this->connectionRepository->findByUser($user) as $connection) {
            $sessionId = $connection->getSessionId();
            if (!$readSessions || !$connection->isUsable() || null === $sessionId) {
                continue;
            }

            try {
                $session = $this->client->getSession($sessionId);
            } catch (\RuntimeException|HttpClientException $e) {
                // A forgotten session answers without JSON: its accounts fall
                // back on looking alike, as an expired one's do.
                $warnings[] = sprintf('%s : session illisible (%s)', $connection->getBankName(), $e->getMessage());
                continue;
            }

            $listed = $session['accounts_data'] ?? $session['accounts'] ?? [];
            foreach (\is_array($listed) ? $listed : [] as $remote) {
                if (!\is_array($remote) || !\is_string($remote['uid'] ?? null)) {
                    continue;
                }
                $key = EnableBankingClient::accountKey($remote);
                if (null !== $key) {
                    $byUid[(string) $connection->getId()][$remote['uid']] = $key;
                }
            }
        }

        $keys = [];
        foreach (null === $user ? $this->accountRepository->findAll() : $this->accountRepository->findByUser($user) as $account) {
            $key = $account->getExternalKey()
                ?? $byUid[(string) $account->getBankConnection()?->getId()][(string) $account->getExternalAccountId()]
                ?? null;
            if (null !== $key) {
                $keys[(string) $account->getId()] = $key;
            }
        }

        return $keys;
    }

    /**
     * @param list<Account>         $accounts oldest first
     * @param array<string, string> $keys
     *
     * @return array{0: list<list<Account>>, 1: list<Account>}
     */
    private function group(array $accounts, array $keys): array
    {
        $groups = [];

        foreach ($accounts as $account) {
            $key = $keys[(string) $account->getId()] ?? null;
            if (null !== $key) {
                $groups[(string) $account->getUser()->getId().'|key|'.$key][] = $account;
            }
        }

        $ambiguous = [];
        foreach ($accounts as $account) {
            if (isset($keys[(string) $account->getId()]) || null === $account->getBankConnection()) {
                continue;
            }

            $candidates = [];
            foreach ($groups as $groupKey => $members) {
                foreach ($members as $member) {
                    if ($this->isCopyOf($account, $member)) {
                        $candidates[] = $groupKey;
                        break;
                    }
                }
            }

            if (1 === \count($candidates)) {
                $groups[$candidates[0]][] = $account;
            } elseif ([] === $candidates) {
                $groups['lookalike|'.$account->getId()][] = $account;
            } else {
                $ambiguous[] = $account;
            }
        }

        $duplicated = [];
        foreach ($groups as $members) {
            if (\count($members) > 1) {
                usort($members, static fn (Account $a, Account $b) => strcmp((string) $a->getId(), (string) $b->getId()));
                $duplicated[] = $members;
            }
        }

        return [$duplicated, $ambiguous];
    }

    /**
     * An account without identification is a copy of another when both sit on
     * the same connection under the same name and currency, and show the same
     * balance or the same movements. The balance alone misses the copy a dead
     * session left behind, frozen at its last sync; the movements do not.
     */
    private function isCopyOf(Account $account, Account $other): bool
    {
        if ($this->signature($account) !== $this->signature($other)) {
            return false;
        }

        return $account->getBalanceCents() === $other->getBalanceCents()
            || $this->sharesMovements($account, $other);
    }

    private function signature(Account $account): string
    {
        return implode('|', [
            (string) $account->getUser()->getId(),
            (string) $account->getBankConnection()?->getId(),
            mb_strtolower(trim($account->getName())),
            $account->getCurrency(),
        ]);
    }

    /**
     * Over the days both accounts cover, more than half the movements of the
     * busier one are on the other too, same day and amount — the label left
     * out, the bank rewording it between two reads. Two real accounts of one
     * holder share a fee now and then, never most of their history.
     */
    private function sharesMovements(Account $account, Account $other): bool
    {
        $mine = $this->movementDays($account);
        $theirs = $this->movementDays($other);
        if ([] === $mine || [] === $theirs) {
            return false;
        }

        $from = max(min(array_keys($mine)), min(array_keys($theirs)));
        $to = min(max(array_keys($mine)), max(array_keys($theirs)));

        $inRange = static fn (array $days) => array_filter(
            $days,
            static fn (string $day) => $day >= $from && $day <= $to,
            ARRAY_FILTER_USE_KEY,
        );
        $mine = $inRange($mine);
        $theirs = $inRange($theirs);

        $shared = 0;
        $counted = [0, 0];
        foreach ($mine as $day => $amounts) {
            $counted[0] += array_sum($amounts);
            foreach ($amounts as $amount => $count) {
                $shared += min($count, $theirs[$day][$amount] ?? 0);
            }
        }
        foreach ($theirs as $amounts) {
            $counted[1] += array_sum($amounts);
        }

        return 2 * $shared > max($counted);
    }

    /**
     * How many movements of each amount the account holds, day by day.
     *
     * @return array<string, array<int, int>>
     */
    private function movementDays(Account $account): array
    {
        if (isset($this->movementDays[(string) $account->getId()])) {
            return $this->movementDays[(string) $account->getId()];
        }

        $days = [];
        foreach ($this->transactionRepository->findByAccount($account) as $transaction) {
            $day = $transaction->getBookedAt()->format('Y-m-d');
            $amount = $transaction->getAmountCents();
            $days[$day][$amount] = ($days[$day][$amount] ?? 0) + 1;
        }

        return $this->movementDays[(string) $account->getId()] = $days;
    }

    /**
     * @param list<Account>         $group oldest first
     * @param array<string, string> $keys
     *
     * @return array{survivor: string, name: string, merged: list<string>, moved: int, dropped: int}
     */
    private function merge(array $group, array $keys, bool $dryRun): array
    {
        $survivor = array_shift($group);
        $newest = end($group) ?: $survivor;
        $moved = 0;
        $dropped = 0;
        $touched = [];
        $removed = [];

        $survivorTransactions = $this->transactionRepository->findByAccount($survivor);

        foreach ($group as $copy) {
            // Each copy is compared with the survivor as it stands, so a
            // movement three copies hold ends up once, not twice.
            $pool = $this->pool($survivorTransactions);

            $copyTransactions = $this->transactionRepository->findByAccount($copy);
            usort($copyTransactions, static fn (Transaction $a, Transaction $b) => strcmp((string) $a->getId(), (string) $b->getId()));

            foreach ($copyTransactions as $transaction) {
                $twin = $this->claimTwin($pool, $transaction);

                if (null === $twin) {
                    ++$moved;
                    if (!$dryRun) {
                        $transaction->setAccount($survivor);
                        $touched[(string) $transaction->getId()] = $transaction;
                    }
                    $survivorTransactions[] = $transaction;
                    continue;
                }

                ++$dropped;
                if (!$dryRun) {
                    $this->carryOver($transaction, $twin, $touched);
                    $removed[] = $transaction;
                }
            }

            if (!$dryRun) {
                $removed[] = $copy;
            }
        }

        $report = [
            'survivor' => (string) $survivor->getId(),
            'name' => $survivor->getName(),
            'merged' => array_map(static fn (Account $copy) => (string) $copy->getId(), $group),
            'moved' => $moved,
            'dropped' => $dropped,
        ];

        if ($dryRun) {
            return $report;
        }

        // The newest copy is the one the current session names.
        if ($newest !== $survivor) {
            $survivor->setExternalAccountId($newest->getExternalAccountId());
            $survivor->setBankConnection($newest->getBankConnection());
            $survivor->setBalanceCents($newest->getBalanceCents());
        }
        // A survivor older than identifications takes the one a copy carries.
        foreach ([$survivor, ...$group] as $member) {
            $survivor->setExternalKey($survivor->getExternalKey() ?? $keys[(string) $member->getId()] ?? null);
        }
        foreach ($group as $copy) {
            $survivor->setIsCushion($survivor->isCushion() || $copy->isCushion());
            if (!$copy->isClosed()) {
                $survivor->setClosedAt(null);
            }
        }

        foreach ($removed as $entity) {
            $this->em->remove($entity);
        }
        $this->em->flush();

        $this->broadcaster->broadcast($survivor);
        foreach ($touched as $transaction) {
            $this->broadcaster->broadcast($transaction);
        }
        foreach ($removed as $entity) {
            $this->broadcaster->broadcastRemoval($entity, $entity instanceof Account ? 'accounts' : 'transactions');
        }

        return $report;
    }

    /**
     * The survivor's movements, by what makes two of them the same: first the
     * exact line, then — the bank having reworded the label between two reads
     * — the same day, amount and payee.
     *
     * @param list<Transaction> $transactions
     *
     * @return array{exact: array<string, list<Transaction>>, payee: array<string, list<Transaction>>, reference: array<string, Transaction>}
     */
    private function pool(array $transactions): array
    {
        $pool = ['exact' => [], 'payee' => [], 'reference' => []];

        foreach ($transactions as $transaction) {
            if (null !== $transaction->getExternalId()) {
                $pool['reference'][$transaction->getExternalId()] = $transaction;
            }
            $pool['exact'][$this->exactKey($transaction)][] = $transaction;
            if (null !== $payee = $this->payeeKey($transaction)) {
                $pool['payee'][$payee][] = $transaction;
            }
        }

        return $pool;
    }

    /**
     * The survivor's copy of this movement, if it has one not already taken.
     *
     * @param array{exact: array<string, list<Transaction>>, payee: array<string, list<Transaction>>, reference: array<string, Transaction>} $pool
     */
    private function claimTwin(array &$pool, Transaction $transaction): ?Transaction
    {
        $twin = null;

        if (null !== $transaction->getExternalId() && isset($pool['reference'][$transaction->getExternalId()])) {
            $twin = $pool['reference'][$transaction->getExternalId()];
        } else {
            $candidates = array_merge(
                $pool['exact'][$this->exactKey($transaction)] ?? [],
                null !== ($payee = $this->payeeKey($transaction)) ? $pool['payee'][$payee] ?? [] : [],
            );
            foreach ($candidates as $candidate) {
                // Two different references are two different movements.
                if (null === $transaction->getExternalId() || null === $candidate->getExternalId()) {
                    $twin = $candidate;
                    break;
                }
            }
        }

        if (null === $twin) {
            return null;
        }

        // Taken once: a second identical movement on the copy is a second one.
        foreach (['exact', 'payee'] as $kind) {
            foreach ($pool[$kind] as $key => $list) {
                $pool[$kind][$key] = array_values(array_filter($list, static fn (Transaction $t) => $t !== $twin));
            }
        }
        if (null !== $twin->getExternalId()) {
            unset($pool['reference'][$twin->getExternalId()]);
        }

        return $twin;
    }

    private function exactKey(Transaction $transaction): string
    {
        return implode('|', [
            $transaction->getBookedAt()->format('Y-m-d'),
            (string) $transaction->getAmountCents(),
            mb_strtolower(trim($transaction->getLabel())),
        ]);
    }

    private function payeeKey(Transaction $transaction): ?string
    {
        $payee = $transaction->getCounterpartyKey();

        return null === $payee || '' === $payee ? null : implode('|', [
            $transaction->getBookedAt()->format('Y-m-d'),
            (string) $transaction->getAmountCents(),
            $payee,
        ]);
    }

    /**
     * What the owner said about the copy that is dropped is not lost: its
     * category, its flags and its transfer pairing pass to the twin kept.
     *
     * @param array<string, Transaction> $touched
     */
    private function carryOver(Transaction $copy, Transaction $twin, array &$touched): void
    {
        $changed = false;

        if (null === $twin->getCategory() && null !== $copy->getCategory()) {
            $twin->assignCategory($copy->getCategory(), $copy->getCategorySource());
            $changed = true;
        }
        if ($copy->isExceptional() && !$twin->isExceptional()) {
            $twin->setIsExceptional(true);
            $changed = true;
        }
        if (RetrospectVerdict::Unrated === $twin->getRetrospect() && RetrospectVerdict::Unrated !== $copy->getRetrospect()) {
            $twin->setRetrospect($copy->getRetrospect());
            $changed = true;
        }
        if (null === $twin->getExternalId() && null !== $copy->getExternalId()) {
            $twin->setExternalId($copy->getExternalId());
        }

        $leg = $copy->getCounterpart();
        if ($copy->isInternalTransfer() && !$twin->isInternalTransfer() && null !== $leg && $leg !== $twin) {
            // The kind travels too: a rejection stays a rejection, never a transfer.
            $twin->pairWith($leg, $copy->getTransferKind(), $copy->getTransferSource());
            $touched[(string) $leg->getId()] = $leg;
            $changed = true;
        } elseif (null !== $leg && $leg->getCounterpart() === $copy) {
            // The other leg would point at nothing once the copy is gone.
            $copy->releaseInternalTransfer($copy->getTransferSource());
            $touched[(string) $leg->getId()] = $leg;
        }

        if ($changed) {
            $touched[(string) $twin->getId()] = $twin;
        }
    }
}
