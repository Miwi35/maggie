<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Exception\RecurringAttachmentException;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurrenceSchedule;
use Maggie\Finance\Service\RecurringMatch;
use Maggie\Finance\Service\TransactionMatcher;

/**
 * Attaches the real transactions to the occurrences of the recurring
 * operations (spec « opérations récurrentes », decision 4).
 *
 * The counterparty says who the money is from, not whether the line belongs
 * to the series: a line is attached only when the shared engine recognises
 * it, its occurrence is still free, and both its date and its amount hold.
 * One tolerance broken is a proposal the owner confirms; both broken, or the
 * occurrence already settled, is a one-off — a purchase from the same payee
 * is never generalised. Nothing but an attachment is ever written.
 *
 * Runs as a line lands, as a catch-up pass over the history, and by hand.
 */
class AttachRecurringTransactions
{
    /** A measured reference amount follows the average of this many latest attachments. */
    public const int RECALIBRATION_WINDOW = 3;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly RecurrenceSchedule $schedule,
        private readonly TransactionMatcher $matcher,
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    /**
     * One line, as it lands, before it is flushed: attached in place when it
     * qualifies — with the series' category and a recalibrated reference —
     * else the proposal it makes, if any. The caller flushes, then
     * broadcasts the series of an attached match.
     */
    public function attachFor(Transaction $transaction): ?RecurringMatch
    {
        $series = $this->operationRepository->findByUser($transaction->getUser());
        if ([] === $series) {
            return null;
        }

        $match = $this->decide($transaction, $series, $this->transactionRepository->findTakenOccurrences($transaction->getUser()), []);

        if (null !== $match && $match->attached && null !== $match->operation && null !== $match->occurrenceOn) {
            $transaction->attachToRecurring($match->operation, $match->occurrenceOn, RecurringLinkSource::Auto);
            $this->recalibrate($match->operation, $transaction);
        }

        return $match;
    }

    /**
     * Catch-up pass over one user's history. Repeats until a round attaches
     * nothing, so a reference recalibrated by one round is the one the next
     * round reads — and a second pass starts from that fixed point and
     * changes nothing.
     *
     * @param int|null $limitDays how far back to look, or null for the whole history
     * @param bool     $dryRun    report what would be attached without writing anything
     *
     * @return array{dryRun: bool, scanned: int, attached: int, proposed: int, attachments: list<array<string, mixed>>, proposals: list<array<string, mixed>>}
     */
    public function execute(User $user, ?int $limitDays = null, bool $dryRun = false): array
    {
        $since = null === $limitDays ? null : (new \DateTimeImmutable('today'))->modify(sprintf('-%d days', $limitDays));
        $candidates = $this->transactionRepository->findRecurringCandidates($user, $since);
        $series = $this->operationRepository->findByUser($user);
        $taken = $this->transactionRepository->findTakenOccurrences($user);

        $reference = [];
        $recent = [];
        foreach ($series as $operation) {
            $id = (string) $operation->getId();
            $reference[$id] = $operation->getReferenceAmountCents();
            $recent[$id] = $this->recentAttachments($operation);
        }

        $attached = [];
        $proposals = [];
        $pending = [] === $series ? [] : $candidates;

        while ([] !== $pending) {
            $claims = [];
            $proposals = [];
            foreach ($pending as $transaction) {
                $match = $this->decide($transaction, $series, $taken, $reference);
                if (null === $match) {
                    continue;
                }
                if ($match->attached) {
                    $claims[(string) $match->key()][] = $match;
                } else {
                    $proposals[] = $match;
                }
            }

            if ([] === $claims) {
                break;
            }

            // Several lines for one occurrence: the closest wins, the others
            // find it taken next round and stay one-offs.
            ksort($claims);
            $won = [];
            foreach ($claims as $key => $contenders) {
                usort($contenders, RecurringMatch::compare(...));
                $winner = $contenders[0];
                $operationId = (string) $winner->operation?->getId();

                $taken[$key] = (string) $winner->transaction->getId();
                $recent[$operationId][] = [
                    'on' => $winner->occurrenceOn?->format('Y-m-d') ?? '',
                    'id' => (string) $winner->transaction->getId(),
                    'amount' => $winner->transaction->getAmountCents(),
                ];
                if (null !== $winner->operation) {
                    $reference[$operationId] = $this->recalibratedAmount($winner->operation, $recent[$operationId]) ?? $reference[$operationId];
                }

                $attached[] = $winner;
                $won[(string) $winner->transaction->getId()] = true;
            }

            $pending = array_values(array_filter($pending, static fn (Transaction $t) => !isset($won[(string) $t->getId()])));
        }

        if (!$dryRun && [] !== $attached) {
            $this->apply($attached, $series, $reference);
        }

        usort($proposals, static fn (RecurringMatch $a, RecurringMatch $b) => [$a->transaction->getBookedAt()->format('Y-m-d'), (string) $a->transaction->getId()]
            <=> [$b->transaction->getBookedAt()->format('Y-m-d'), (string) $b->transaction->getId()]);

        return [
            'dryRun' => $dryRun,
            'scanned' => \count($candidates),
            'attached' => \count($attached),
            'proposed' => \count($proposals),
            'attachments' => array_map(static fn (RecurringMatch $m) => $m->toArray(), $attached),
            'proposals' => array_map(static fn (RecurringMatch $m) => $m->toArray(), $proposals),
        ];
    }

    /**
     * The owner's own attachment, sealed `manual`: the automatic pass never
     * moves it. The occurrence defaults to the one nearest the booking day.
     * Changes the entities in place; the caller flushes, then broadcasts the
     * series returned — the one attached to, and the one left, if any.
     *
     * @return list<RecurringOperation> the series whose attachments changed
     *
     * @throws RecurringAttachmentException when the line cannot be this occurrence — the occurrence settled by another line included, before the unique constraint says so
     */
    public function attachByHand(Transaction $transaction, RecurringOperation $operation, ?\DateTimeImmutable $occurrenceOn = null): array
    {
        if (TransferKind::None !== $transaction->getTransferKind()) {
            throw new RecurringAttachmentException('An internal transfer or a rejected payment is neither an expense nor an income: it cannot be an occurrence of a recurring operation.');
        }
        if (!$operation->getAccount()->getId()->equals($transaction->getAccount()->getId())) {
            throw new RecurringAttachmentException(sprintf('The recurring operation "%s" is on another account than this transaction.', $operation->getLabel()));
        }
        if ($transaction->getCurrency() !== $operation->getAccount()->getCurrency()) {
            throw new RecurringAttachmentException(sprintf('The recurring operation "%s" is in %s, this transaction in %s.', $operation->getLabel(), $operation->getAccount()->getCurrency(), $transaction->getCurrency()));
        }
        if (0 === $transaction->getAmountCents() || ($transaction->getAmountCents() > 0) !== ($operation->getReferenceAmountCents() > 0)) {
            throw new RecurringAttachmentException(sprintf('The recurring operation "%s" is %s, this transaction %s.', $operation->getLabel(), $operation->getReferenceAmountCents() > 0 ? 'an income' : 'an expense', $transaction->getAmountCents() > 0 ? 'an income' : 'an expense'));
        }

        $occurrenceOn = $this->occurrenceOf($operation, $occurrenceOn ?? $transaction->getBookedAt(), null === $occurrenceOn);

        $holder = $this->transactionRepository->findAttachedToOccurrence($operation, $occurrenceOn);
        if (null !== $holder && $holder !== $transaction && $holder->getRecurringOperation() === $operation) {
            throw new RecurringAttachmentException(sprintf('The occurrence of %s of "%s" is already settled by the transaction "%s" of %s: detach it first.', $occurrenceOn->format('Y-m-d'), $operation->getLabel(), $holder->getLabel(), $holder->getBookedAt()->format('Y-m-d')));
        }

        $former = $this->formerSeries($transaction);
        $transaction->attachToRecurring($operation, $occurrenceOn, RecurringLinkSource::Manual);

        return $this->recalibrateAround($transaction, $former, $operation);
    }

    /**
     * The owner takes the line out of its series, for good: sealed `manual`,
     * the automatic pass never attaches it again. The category stays.
     *
     * @return list<RecurringOperation> the series it left, if any
     */
    public function detachByHand(Transaction $transaction): array
    {
        $former = $this->formerSeries($transaction);
        $transaction->detachFromRecurring(RecurringLinkSource::Manual);

        return $this->recalibrateAround($transaction, $former, null);
    }

    /**
     * The series the line was attached to as loaded — over REST the
     * deserializer has already written the new one on this very object.
     */
    private function formerSeries(Transaction $transaction): ?RecurringOperation
    {
        $original = $this->em->getUnitOfWork()->getOriginalEntityData($transaction);

        if (!\array_key_exists('recurringOperation', $original)) {
            return $transaction->getRecurringOperation();
        }

        return $original['recurringOperation'] instanceof RecurringOperation ? $original['recurringOperation'] : null;
    }

    /**
     * What one line makes against every series of its owner. A line no series
     * claims — or several series claim with equal right — is never attached.
     *
     * @param RecurringOperation[]  $series
     * @param array<string, string> $taken     occurrence key => the transaction settling it
     * @param array<string, int>    $reference series id => the reference to read, when not the stored one
     */
    private function decide(Transaction $transaction, array $series, array $taken, array $reference): ?RecurringMatch
    {
        if (!self::isEligible($transaction)) {
            return null;
        }

        $attach = [];
        $propose = [];
        foreach ($series as $operation) {
            if (!$operation->getAccount()->getId()->equals($transaction->getAccount()->getId())
                || $transaction->getCurrency() !== $operation->getAccount()->getCurrency()
                || !$this->matcher->matches($operation->matchCriteria(), $transaction)) {
                continue;
            }

            $occurrenceOn = $this->schedule->occurrenceFor($operation, $transaction->getBookedAt());
            if (null === $occurrenceOn || !$operation->isActiveOn($occurrenceOn)) {
                continue;
            }

            // Already settled: a second debit of the month is a one-off.
            $holder = $taken[TransactionRepository::occurrenceKey((string) $operation->getId(), $occurrenceOn)] ?? null;
            if (null !== $holder && $holder !== (string) $transaction->getId()) {
                continue;
            }

            $match = RecurringMatch::judge(
                $transaction,
                $operation,
                $occurrenceOn,
                self::signedDays($occurrenceOn, $transaction->getBookedAt()),
                $reference[(string) $operation->getId()] ?? $operation->getReferenceAmountCents(),
            );

            if (null === $match) {
                continue;
            }

            if ($match->attached) {
                $attach[] = $match;
            } else {
                $propose[] = $match;
            }
        }

        $claims = [] !== $attach ? $attach : $propose;

        return match (\count($claims)) {
            0 => null,
            1 => $claims[0],
            default => RecurringMatch::ambiguous($transaction, $claims),
        };
    }

    /** Anything else is the owner's to decide, or not a spending at all. */
    private static function isEligible(Transaction $transaction): bool
    {
        return null === $transaction->getRecurringOperation()
            && RecurringLinkSource::Auto === $transaction->getRecurringSource()
            && TransferKind::None === $transaction->getTransferKind()
            && TransactionStatus::ToArbitrate !== $transaction->getStatus();
    }

    /**
     * The occurrence a day names. Given by the owner, it must be a due date
     * of the series; read from the booking day, it is the nearest one.
     */
    private function occurrenceOf(RecurringOperation $operation, \DateTimeImmutable $day, bool $nearest): \DateTimeImmutable
    {
        $day = $day->setTime(0, 0);

        if ($nearest) {
            $occurrenceOn = $this->schedule->occurrenceFor($operation, $day);
            if (null === $occurrenceOn || !$operation->isActiveOn($occurrenceOn)) {
                throw new RecurringAttachmentException(sprintf('The recurring operation "%s" has no occurrence near %s.', $operation->getLabel(), $day->format('Y-m-d')));
            }

            return $occurrenceOn;
        }

        $occurrences = $this->schedule->occurrencesBetween($operation, $day, $day->modify('+1 day'));
        if ([] === $occurrences) {
            $nearestOn = $this->schedule->occurrenceFor($operation, $day);

            throw new RecurringAttachmentException(sprintf('%s is not a due date of the recurring operation "%s"%s.', $day->format('Y-m-d'), $operation->getLabel(), null === $nearestOn ? '' : sprintf(' (the nearest is %s)', $nearestOn->format('Y-m-d'))));
        }

        return $occurrences[0];
    }

    /**
     * Writes a catch-up pass: every attachment, then every reference the pass
     * recalibrated, in one flush; then publishes the lines and their series.
     *
     * @param list<RecurringMatch> $attached
     * @param RecurringOperation[] $series
     * @param array<string, int>   $reference
     */
    private function apply(array $attached, array $series, array $reference): void
    {
        $touched = [];
        foreach ($attached as $match) {
            if (null === $match->operation || null === $match->occurrenceOn) {
                continue;
            }
            $match->transaction->attachToRecurring($match->operation, $match->occurrenceOn, RecurringLinkSource::Auto);
            $touched[(string) $match->operation->getId()] = $match->operation;
        }

        foreach ($series as $operation) {
            $id = (string) $operation->getId();
            if (ReferenceAmountSource::Measured === $operation->getReferenceSource() && $reference[$id] !== $operation->getReferenceAmountCents()) {
                $operation->setReferenceAmountCents($reference[$id]);
                $touched[$id] = $operation;
            }
        }

        $this->em->flush();

        foreach ($attached as $match) {
            $this->broadcaster->broadcast($match->transaction);
        }
        foreach ($touched as $operation) {
            $this->broadcaster->broadcast($operation);
        }
    }

    /**
     * Recalibrates the series a line just joined and the one it left.
     *
     * @return list<RecurringOperation>
     */
    private function recalibrateAround(Transaction $transaction, ?RecurringOperation $former, ?RecurringOperation $current): array
    {
        $changed = [];
        foreach ([$former, $current] as $operation) {
            if (null === $operation || \in_array($operation, $changed, true)) {
                continue;
            }
            $this->recalibrate($operation, $transaction);
            $changed[] = $operation;
        }

        return $changed;
    }

    /** Moves a measured reference to what its latest attachments say, `$changed` read as it stands in memory. */
    private function recalibrate(RecurringOperation $operation, Transaction $changed): void
    {
        $amount = $this->recalibratedAmount($operation, $this->recentAttachments($operation, $changed));

        if (null !== $amount) {
            $operation->setReferenceAmountCents($amount);
        }
    }

    /**
     * The latest attachments of a series, from the database — except
     * `$changed`, whose unflushed state is the one that counts.
     *
     * @return list<array{on: string, id: string, amount: int}>
     */
    private function recentAttachments(RecurringOperation $operation, ?Transaction $changed = null): array
    {
        $rows = [];
        foreach ($this->transactionRepository->findLastAttached($operation, self::RECALIBRATION_WINDOW + 1) as $transaction) {
            if ($transaction !== $changed) {
                $rows[] = self::row($transaction);
            }
        }

        if (null !== $changed && $changed->getRecurringOperation() === $operation && null !== $changed->getRecurringOccurrenceOn()) {
            $rows[] = self::row($changed);
        }

        return $rows;
    }

    /** @return array{on: string, id: string, amount: int} */
    private static function row(Transaction $transaction): array
    {
        return [
            'on' => $transaction->getRecurringOccurrenceOn()?->format('Y-m-d') ?? '',
            'id' => (string) $transaction->getId(),
            'amount' => $transaction->getAmountCents(),
        ];
    }

    /**
     * The average of the latest attachments, at most three, `intdiv`, sign
     * kept — counted back from the latest one, and only while they stay
     * within the tolerance of it: a price change is a break, and averaging
     * across it would put the reference on a price nobody pays. Null for a
     * declared amount, which never moves, or with nothing attached.
     *
     * @param list<array{on: string, id: string, amount: int}> $rows
     */
    private function recalibratedAmount(RecurringOperation $operation, array $rows): ?int
    {
        if (ReferenceAmountSource::Measured !== $operation->getReferenceSource() || [] === $rows) {
            return null;
        }

        usort($rows, static fn (array $a, array $b) => [$b['on'], $b['id']] <=> [$a['on'], $a['id']]);

        $latest = $rows[0]['amount'];
        $amounts = [];
        foreach ($rows as $row) {
            if (\count($amounts) >= self::RECALIBRATION_WINDOW || !$operation->acceptsAmount($row['amount'], $latest)) {
                break;
            }
            $amounts[] = abs($row['amount']);
        }

        $average = intdiv(array_sum($amounts), \count($amounts));

        return $latest < 0 ? -$average : $average;
    }

    /** Whole calendar days from the due date to the booking day: positive when late. */
    private static function signedDays(\DateTimeImmutable $occurrenceOn, \DateTimeImmutable $bookedAt): int
    {
        $utc = new \DateTimeZone('UTC');
        $from = new \DateTimeImmutable($occurrenceOn->format('Y-m-d'), $utc);
        $to = new \DateTimeImmutable($bookedAt->format('Y-m-d'), $utc);

        return (int) $from->diff($to)->format('%r%a');
    }
}
