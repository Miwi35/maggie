<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Recognises a movement between two of the owner's own accounts.
 *
 * Such a movement is neither an expense nor an income: it is marked on both
 * lines so every aggregate leaves it out. Runs on creation, like the rule
 * engine, and as a catch-up pass over the history.
 *
 * The pairing is deterministic — closest date first, then oldest `bookedAt`,
 * then smallest ULID — and at most one leg against one: three debits of the
 * same amount are common (a monthly standing order), and without a total order
 * two catch-up passes would not agree, so the owner's figure would move
 * without anything having changed.
 */
class DetectInternalTransfers
{
    /**
     * An interbank transfer takes one to three business days; four covers a
     * weekend without opening the door to two look-alike movements of the same
     * week. In hard on purpose (shape decision 5).
     */
    public const int WINDOW_DAYS = 4;

    private const array CONSUMED = [TransactionStatus::Spent, TransactionStatus::Committed];

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * The other leg of this movement: closest date, then oldest, then smallest
     * ULID.
     */
    public function detectFor(Transaction $transaction): ?Transaction
    {
        if (!$this->isEligible($transaction)) {
            return null;
        }

        $candidates = $this->transactionRepository->findTransferCandidates($transaction, self::WINDOW_DAYS);
        if ([] === $candidates) {
            return null;
        }

        $reference = $transaction->getBookedAt();
        usort($candidates, static fn (Transaction $a, Transaction $b) => [self::gapInDays($reference, $a), self::sortKey($a)]
            <=> [self::gapInDays($reference, $b), self::sortKey($b)]);

        return $candidates[0];
    }

    /**
     * Pairs the history of one user, writing through the bus so both legs
     * publish to Mercure and get reindexed.
     *
     * @param int|null $limitDays how far back to look, or null for the whole history
     * @param bool     $dryRun    report what would be paired without writing anything
     *
     * @return array{matched: int, scanned: int, dryRun: bool, pairs: list<array{transactionId: string, counterpartId: string, amountCents: int, bookedAt: string, counterpartBookedAt: string, label: string, counterpartLabel: string}>}
     */
    public function execute(User $user, ?int $limitDays = null, bool $dryRun = false): array
    {
        $since = null === $limitDays
            ? null
            : new \DateTimeImmutable(sprintf('midnight -%d days', $limitDays));

        $transactions = $this->transactionRepository->findUnpairedForUser($user, $since);

        // Every possible pairing first, then the closest dates win: a line can
        // be the only candidate of one line and the third choice of another,
        // and taking them in the order the history happens to be stored would
        // pair the farthest one just because it was read first.
        $candidates = $this->candidatePairs($transactions);

        /** @var array<string, true> $claimed */
        $claimed = [];
        $pairs = [];

        foreach ($candidates as $candidate) {
            [$left, $right] = [$candidate['left'], $candidate['right']];
            $leftId = (string) $left->getId();
            $rightId = (string) $right->getId();

            if (isset($claimed[$leftId]) || isset($claimed[$rightId])) {
                continue;
            }

            $claimed[$leftId] = true;
            $claimed[$rightId] = true;

            $pairs[] = [
                'transactionId' => $leftId,
                'counterpartId' => $rightId,
                'amountCents' => $left->getAmountCents(),
                'bookedAt' => $left->getBookedAt()->format('Y-m-d'),
                'counterpartBookedAt' => $right->getBookedAt()->format('Y-m-d'),
                'label' => $left->getLabel(),
                'counterpartLabel' => $right->getLabel(),
            ];

            if (!$dryRun) {
                $this->pair($user, $leftId, $rightId);
            }
        }

        return [
            'matched' => \count($pairs),
            'scanned' => \count($transactions),
            'dryRun' => $dryRun,
            'pairs' => $pairs,
        ];
    }

    /**
     * Every pair the window allows, closest dates first, each one spelled once.
     *
     * The order is total — gap, then `bookedAt`, then ULID, on the older leg
     * and then on the newer one — so two passes over the same history claim
     * the same pairs, and the owner's figure never moves on its own.
     *
     * @param Transaction[] $transactions
     *
     * @return list<array{left: Transaction, right: Transaction}>
     */
    private function candidatePairs(array $transactions): array
    {
        $pairs = [];

        foreach ($transactions as $transaction) {
            if (!$this->isEligible($transaction)) {
                continue;
            }

            foreach ($this->transactionRepository->findTransferCandidates($transaction, self::WINDOW_DAYS) as $candidate) {
                [$left, $right] = self::order($transaction, $candidate);
                $pairs[(string) $left->getId().'/'.(string) $right->getId()] = [
                    'gap' => self::gapInDays($left->getBookedAt(), $right),
                    'order' => self::sortKey($left).self::sortKey($right),
                    'left' => $left,
                    'right' => $right,
                ];
            }
        }

        usort($pairs, static fn (array $a, array $b) => [$a['gap'], $a['order']] <=> [$b['gap'], $b['order']]);

        return array_map(static fn (array $pair) => ['left' => $pair['left'], 'right' => $pair['right']], $pairs);
    }

    /**
     * The two legs of a pair, older leg first, smallest ULID breaking a tie.
     *
     * @return array{Transaction, Transaction}
     */
    private static function order(Transaction $a, Transaction $b): array
    {
        return self::sortKey($a) <= self::sortKey($b) ? [$a, $b] : [$b, $a];
    }

    private static function sortKey(Transaction $transaction): string
    {
        return $transaction->getBookedAt()->format('Y-m-d').'|'.(string) $transaction->getId().'|';
    }

    /**
     * One command: its handler marks both legs and broadcasts the other one,
     * so each line reaches Mercure and the search index exactly once.
     */
    private function pair(User $user, string $transactionId, string $counterpartId): void
    {
        $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $user->getId(),
            transactionId: $transactionId,
            transferKind: TransferKind::Internal->value,
            transferSource: TransferSource::Auto->value,
            counterpartId: $counterpartId,
        ));
    }

    /**
     * A line the detection may look at: consumed, not paired yet, not judged
     * by hand, and on an account of its own user. A zero amount is left out —
     * two of them are exactly opposite, and neither is a transfer.
     */
    private function isEligible(Transaction $transaction): bool
    {
        return 0 !== $transaction->getAmountCents()
            && null === $transaction->getCounterpart()
            && TransferSource::Manual !== $transaction->getTransferSource()
            && \in_array($transaction->getStatus(), self::CONSUMED, true)
            && $transaction->getAccount()->getUser()->getId()->equals($transaction->getUser()->getId());
    }

    private static function gapInDays(\DateTimeImmutable $reference, Transaction $candidate): int
    {
        return abs((int) $reference->diff($candidate->getBookedAt())->days);
    }
}
