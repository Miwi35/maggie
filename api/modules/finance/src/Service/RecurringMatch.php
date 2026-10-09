<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * What the attachment makes of one transaction: attached to an occurrence, or
 * only proposed — the owner confirms — with the reason it was not attached.
 *
 * A proposal says which tolerance the line broke: `late` / `early` for the
 * date, `amount_up` / `amount_down` for the amount. `ambiguous` is two series
 * claiming the line; it names them, and no single occurrence.
 */
final readonly class RecurringMatch
{
    public const string LATE = 'late';
    public const string EARLY = 'early';
    public const string AMOUNT_UP = 'amount_up';
    public const string AMOUNT_DOWN = 'amount_down';
    public const string AMBIGUOUS = 'ambiguous';

    /** @param list<RecurringMatch> $candidates the claims an ambiguous match could not choose between */
    private function __construct(
        public Transaction $transaction,
        public bool $attached,
        public ?string $reason,
        public ?RecurringOperation $operation,
        public ?\DateTimeImmutable $occurrenceOn,
        public int $dateGapDays,
        public int $referenceAmountCents,
        public array $candidates = [],
    ) {
    }

    /**
     * A transaction against one occurrence of one series: attached when both
     * tolerances hold, proposed when exactly one breaks, nothing otherwise.
     *
     * @param int $dateGapDays signed: positive when the line came after its due date
     */
    public static function judge(
        Transaction $transaction,
        RecurringOperation $operation,
        \DateTimeImmutable $occurrenceOn,
        int $dateGapDays,
        int $referenceAmountCents,
    ): ?self {
        $dateHolds = abs($dateGapDays) <= $operation->getDateToleranceDays();
        $amountHolds = $operation->acceptsAmount($transaction->getAmountCents(), $referenceAmountCents);

        $reason = match (true) {
            $dateHolds && $amountHolds => null,
            $dateHolds => abs($transaction->getAmountCents()) > abs($referenceAmountCents) ? self::AMOUNT_UP : self::AMOUNT_DOWN,
            $amountHolds => $dateGapDays > 0 ? self::LATE : self::EARLY,
            // A one-off purchase from the same payee: never generalised.
            default => false,
        };

        if (false === $reason) {
            return null;
        }

        return new self($transaction, null === $reason, $reason, $operation, $occurrenceOn, $dateGapDays, $referenceAmountCents);
    }

    /** @param non-empty-list<RecurringMatch> $candidates */
    public static function ambiguous(Transaction $transaction, array $candidates): self
    {
        return new self($transaction, false, self::AMBIGUOUS, null, null, 0, 0, $candidates);
    }

    /** The occurrence this match claims, keyed like `TransactionRepository::findTakenOccurrences()`; null when ambiguous. */
    public function key(): ?string
    {
        if (null === $this->operation || null === $this->occurrenceOn) {
            return null;
        }

        return TransactionRepository::occurrenceKey((string) $this->operation->getId(), $this->occurrenceOn);
    }

    /** How far the amount is from the reference, in cents, unsigned. */
    public function amountGapCents(): int
    {
        return abs(abs($this->transaction->getAmountCents()) - abs($this->referenceAmountCents));
    }

    /** How much the amount moved from the reference, in whole percent, signed — `+33` for 13,49 € → 17,99 €. */
    public function amountChangePercent(): int
    {
        if (0 === $this->referenceAmountCents) {
            return 0;
        }

        return intdiv((abs($this->transaction->getAmountCents()) - abs($this->referenceAmountCents)) * 100, abs($this->referenceAmountCents));
    }

    /**
     * The total order every competition is settled by: closest date, then
     * closest amount, then smallest ULID — so two passes agree.
     */
    public static function compare(self $a, self $b): int
    {
        return [abs($a->dateGapDays), $a->amountGapCents(), (string) $a->transaction->getId()]
            <=> [abs($b->dateGapDays), $b->amountGapCents(), (string) $b->transaction->getId()];
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        $transaction = $this->transaction;

        $line = [
            'transactionId' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'amountCents' => $transaction->getAmountCents(),
            'reason' => $this->reason,
        ];

        if (self::AMBIGUOUS === $this->reason) {
            return $line + [
                'candidates' => array_map(static fn (self $c) => $c->claim(), $this->candidates),
            ];
        }

        return $line + $this->claim();
    }

    /** @return array<string, mixed> */
    private function claim(): array
    {
        return [
            'recurringOperationId' => null !== $this->operation ? (string) $this->operation->getId() : null,
            'recurringOperationLabel' => $this->operation?->getLabel(),
            'occurrenceOn' => $this->occurrenceOn?->format('Y-m-d'),
            'dateGapDays' => $this->dateGapDays,
            'referenceAmountCents' => $this->referenceAmountCents,
            'amountChangePercent' => $this->amountChangePercent(),
        ];
    }
}
