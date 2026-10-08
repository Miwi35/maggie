<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
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
 * a copy shares with its original: the connection, the name, the currency and
 * the balance, which every sync rewrites from the bank. A lookalike that
 * would fit two different real accounts is left alone and reported.
 *
 * The oldest account survives: it holds the longest history, and the
 * references the owner made to it. Running it twice merges nothing more.
 */
class MergeDuplicateAccounts
{
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
     * @return array{keysLearnt: int, groups: list<array{survivor: string, name: string, merged: list<string>, moved: int, dropped: int}>, ambiguous: list<string>, warnings: list<string>}
     */
    public function execute(bool $dryRun = true): array
    {
        $warnings = [];
        $keys = $this->learnKeys($warnings);

        $accounts = array_values(array_filter(
            $this->accountRepository->findAll(),
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
    private function learnKeys(array &$warnings): array
    {
        $byUid = [];

        foreach ($this->connectionRepository->findAll() as $connection) {
            $sessionId = $connection->getSessionId();
            if (!$connection->isUsable() || null === $sessionId) {
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
        foreach ($this->accountRepository->findAll() as $account) {
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
        $lookalikes = [];

        foreach ($accounts as $account) {
            $key = $keys[(string) $account->getId()] ?? null;
            if (null !== $key) {
                $groups[(string) $account->getUser()->getId().'|key|'.$key][] = $account;
            }
        }

        // Which keyed groups each lookalike signature points at.
        foreach ($groups as $groupKey => $members) {
            foreach ($members as $member) {
                $lookalikes[$this->signature($member)][$groupKey] = true;
            }
        }

        $ambiguous = [];
        foreach ($accounts as $account) {
            if (isset($keys[(string) $account->getId()]) || null === $account->getBankConnection()) {
                continue;
            }

            $candidates = array_keys($lookalikes[$this->signature($account)] ?? []);

            if (1 === \count($candidates)) {
                $groups[$candidates[0]][] = $account;
            } elseif ([] === $candidates) {
                $groups['lookalike|'.$this->signature($account)][] = $account;
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

    private function signature(Account $account): string
    {
        return implode('|', [
            (string) $account->getUser()->getId(),
            (string) $account->getBankConnection()?->getId(),
            mb_strtolower(trim($account->getName())),
            $account->getCurrency(),
            (string) $account->getBalanceCents(),
        ]);
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
        $survivor->setExternalKey($survivor->getExternalKey() ?? $keys[(string) $survivor->getId()] ?? null);
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
