<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurrenceSchedule;
use Maggie\Finance\Service\RecurringMatch;

/**
 * What the owner reads of their recurring operations: what the series cost a
 * month and a year, when each falls due next, the one that did not come, the
 * price that moved, and the attachments still waiting for a yes.
 *
 * A pure read. Nothing is written and no occurrence is materialised: the
 * history is the lines already attached, the future is `RecurrenceSchedule`,
 * and the proposals are the catch-up pass run as a dry run.
 *
 * Amounts are signed cents, as everywhere in the module: a cost is negative,
 * an income positive. Totals are summed as they stand, without converting
 * between the currencies of the accounts, like the dashboard's balance.
 */
class GetRecurringOperations
{
    public function __construct(
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly RecurrenceSchedule $schedule,
        private readonly AttachRecurringTransactions $attach,
    ) {
    }

    /** @return array{operations: list<array<string, mixed>>, proposals: list<array<string, mixed>>, totals: array<string, int>} */
    public function execute(User $user, ?\DateTimeImmutable $today = null): array
    {
        $today = ($today ?? new \DateTimeImmutable('today'))->setTime(0, 0);
        $day = $today->format('Y-m-d');

        $series = $this->operationRepository->findSeriesByUser($user);
        if ([] === $series) {
            return ['operations' => [], 'proposals' => [], 'totals' => self::totals([], $day)];
        }

        $history = [];
        $taken = [];
        foreach ($this->transactionRepository->findAttachmentRows($user) as $row) {
            $history[$row['operationId']][] = $row;
            $taken[TransactionRepository::occurrenceKey($row['operationId'], new \DateTimeImmutable($row['occurrenceOn']))] = true;
        }

        // A line the pass would attach on its own is in neither list until it runs.
        $proposals = $this->proposals($user);
        $proposed = self::proposedOccurrences($proposals);

        $operations = [];
        foreach ($series as $operation) {
            $operations[] = $this->describe($operation, $history[(string) $operation->getId()] ?? [], $taken, $proposed, $today);
        }

        return [
            'operations' => $operations,
            'proposals' => $proposals,
            'totals' => self::totals($series, $day),
        ];
    }

    /**
     * @param list<array{operationId: string, occurrenceOn: string, bookedAt: string, amountCents: int, transactionId: string}> $attachments oldest occurrence first
     * @param array<string, true>                                                                                               $taken       occurrence key => settled
     * @param array<string, true>                                                                                               $proposed    occurrence key => a proposal already claims it
     *
     * @return array<string, mixed>
     */
    private function describe(RecurringOperation $operation, array $attachments, array $taken, array $proposed, \DateTimeImmutable $today): array
    {
        $last = [] === $attachments ? null : $attachments[\count($attachments) - 1];
        $ended = self::hasEnded($operation, $today->format('Y-m-d'));

        return [
            'id' => (string) $operation->getId(),
            'label' => $operation->getLabel(),
            'categoryId' => (string) $operation->getCategory()->getId(),
            'categoryName' => $operation->getCategory()->getName(),
            'accountId' => (string) $operation->getAccount()->getId(),
            'accountName' => $operation->getAccount()->getName(),
            'accountBank' => $operation->getAccount()->effectiveBank(),
            'period' => $operation->getPeriod()->value,
            'dayRule' => $operation->getDayRule()->value,
            'referenceAmountCents' => $operation->getReferenceAmountCents(),
            'referenceSource' => $operation->getReferenceSource()->value,
            'currency' => $operation->getAccount()->getCurrency(),
            'monthlyCostCents' => $operation->getMonthlyCostCents(),
            'yearlyCostCents' => $operation->getYearlyCostCents(),
            'endsOn' => $operation->getEndsOn()?->format('Y-m-d'),
            'nextOccurrenceOn' => $this->nextOccurrence($operation, $taken, $today)?->format('Y-m-d'),
            'lastAttachedOn' => $last['occurrenceOn'] ?? null,
            'attachmentCount' => \count($attachments),
            'lastOccurrence' => null === $last ? null : $this->lastOccurrence($operation, $attachments),
            'missedOccurrence' => $ended ? null : $this->missedOccurrence($operation, $taken, $proposed, $today),
            'priceChange' => self::priceChange($operation, $attachments),
        ];
    }

    /**
     * The first due date from today on that no line settles yet; null once the series has ended.
     *
     * @param array<string, true> $taken
     */
    private function nextOccurrence(RecurringOperation $operation, array $taken, \DateTimeImmutable $today): ?\DateTimeImmutable
    {
        $next = $this->schedule->nextOccurrenceAfter($operation, $today->modify('-1 day'));

        while (null !== $next && isset($taken[TransactionRepository::occurrenceKey((string) $operation->getId(), $next)])) {
            $next = $this->schedule->nextOccurrenceAfter($operation, $next);
        }

        return $next;
    }

    /**
     * The latest due date that is past its date tolerance — strictly: on the
     * last day of the tolerance the line may still come — when no line
     * settles it, and none is waiting as a proposal either. Only the latest is
     * looked at: once it is settled, an older gap is history, not news.
     *
     * @param array<string, true> $taken
     * @param array<string, true> $proposed
     *
     * @return array{occurrenceOn: string, daysOverdue: int}|null
     */
    private function missedOccurrence(RecurringOperation $operation, array $taken, array $proposed, \DateTimeImmutable $today): ?array
    {
        $cutoff = $today->modify(sprintf('-%d days', $operation->getDateToleranceDays()));
        $months = $operation->getPeriod()->months();
        // A window one step wide always holds an occurrence while the series runs.
        $from = $cutoff->modify(sprintf('-%d days', null === $months ? 8 : $months * 31 + 1));

        $due = $this->schedule->occurrencesBetween($operation, $from, $cutoff);
        if ([] === $due) {
            return null;
        }

        $latest = $due[\count($due) - 1];
        $key = TransactionRepository::occurrenceKey((string) $operation->getId(), $latest);
        if (isset($taken[$key]) || isset($proposed[$key])) {
            return null;
        }

        return [
            'occurrenceOn' => $latest->format('Y-m-d'),
            'daysOverdue' => self::daysBetween($latest->format('Y-m-d'), $today->format('Y-m-d')),
        ];
    }

    /**
     * The latest attached line against what it was attached to: how far from
     * its due date, and from the amount expected *then* — the reference the
     * earlier attachments gave, since the line itself has since moved a
     * measured one. `deviations` lists the tolerances it broke, in the
     * vocabulary of the proposals: a line the owner attached by hand may
     * break both.
     *
     * @param non-empty-list<array{operationId: string, occurrenceOn: string, bookedAt: string, amountCents: int, transactionId: string}> $attachments
     *
     * @return array<string, mixed>
     */
    private function lastOccurrence(RecurringOperation $operation, array $attachments): array
    {
        $last = $attachments[\count($attachments) - 1];

        $earlier = array_map(static fn (array $row) => [
            'on' => $row['occurrenceOn'],
            'id' => $row['transactionId'],
            'amount' => $row['amountCents'],
        ], \array_slice($attachments, 0, -1));
        $reference = $this->attach->recalibratedAmount($operation, $earlier) ?? $operation->getReferenceAmountCents();

        $gap = self::daysBetween($last['occurrenceOn'], $last['bookedAt']);

        $deviations = [];
        if (abs($gap) > $operation->getDateToleranceDays()) {
            $deviations[] = $gap > 0 ? RecurringMatch::LATE : RecurringMatch::EARLY;
        }
        if (!$operation->acceptsAmount($last['amountCents'], $reference)) {
            $deviations[] = abs($last['amountCents']) > abs($reference) ? RecurringMatch::AMOUNT_UP : RecurringMatch::AMOUNT_DOWN;
        }

        return [
            'transactionId' => $last['transactionId'],
            'occurrenceOn' => $last['occurrenceOn'],
            'bookedAt' => $last['bookedAt'],
            'amountCents' => $last['amountCents'],
            'dateGapDays' => $gap,
            'expectedAmountCents' => $reference,
            'deviations' => $deviations,
        ];
    }

    /**
     * The price the series is paid at now, against the one before it: the run
     * of latest attachments at the same amount is the new price, `since` is
     * where it began. Null while the amount has not moved, or moved within the
     * series' amount tolerance — a variable bill is not a price change every
     * month. The percent is read on the absolute amounts: a dearer expense and
     * a bigger income are both positive.
     *
     * @param list<array{operationId: string, occurrenceOn: string, bookedAt: string, amountCents: int, transactionId: string}> $attachments
     *
     * @return array{previousCents: int, currentCents: int, percent: int, since: string}|null
     */
    private static function priceChange(RecurringOperation $operation, array $attachments): ?array
    {
        $count = \count($attachments);
        if ($count < 2) {
            return null;
        }

        $current = $attachments[$count - 1]['amountCents'];
        $start = $count - 1;
        while ($start > 0 && $attachments[$start - 1]['amountCents'] === $current) {
            --$start;
        }

        if (0 === $start) {
            return null;
        }

        $previous = $attachments[$start - 1]['amountCents'];
        if (0 === $previous || $operation->acceptsAmount($current, $previous)) {
            return null;
        }

        $percent = intdiv((abs($current) - abs($previous)) * 100, abs($previous));

        return [
            'previousCents' => $previous,
            'currentCents' => $current,
            'percent' => $percent,
            'since' => $attachments[$start]['occurrenceOn'],
        ];
    }

    /**
     * The attachments the catch-up pass would only propose, as a dry run:
     * nothing is written, and the owner confirms by attaching the line.
     * `recurringOperationId` is always present, null when two series claim the
     * line and `candidates` says which.
     *
     * @return list<array<string, mixed>>
     */
    private function proposals(User $user): array
    {
        $report = $this->attach->execute($user, null, true);

        return array_map(static fn (array $proposal) => $proposal + ['recurringOperationId' => null], $report['proposals']);
    }

    /**
     * @param list<array<string, mixed>> $proposals
     *
     * @return array<string, true> occurrence key => claimed by a proposal
     */
    private static function proposedOccurrences(array $proposals): array
    {
        $claims = [];
        foreach ($proposals as $proposal) {
            foreach ([$proposal, ...($proposal['candidates'] ?? [])] as $claim) {
                if (\is_string($claim['recurringOperationId'] ?? null) && \is_string($claim['occurrenceOn'] ?? null)) {
                    $claims[TransactionRepository::occurrenceKey($claim['recurringOperationId'], new \DateTimeImmutable($claim['occurrenceOn']))] = true;
                }
            }
        }

        return $claims;
    }

    /**
     * Costs and incomes apart, over the series that have not ended: a series
     * that has not started yet is counted, the rent of the flat the owner
     * moves into next month is already a commitment.
     *
     * @param RecurringOperation[] $series
     *
     * @return array{monthlyCostCents: int, yearlyCostCents: int, monthlyIncomeCents: int, activeCount: int}
     */
    private static function totals(array $series, string $day): array
    {
        $totals = ['monthlyCostCents' => 0, 'yearlyCostCents' => 0, 'monthlyIncomeCents' => 0, 'activeCount' => 0];

        foreach ($series as $operation) {
            if (self::hasEnded($operation, $day)) {
                continue;
            }

            ++$totals['activeCount'];
            if ($operation->getReferenceAmountCents() < 0) {
                $totals['monthlyCostCents'] += $operation->getMonthlyCostCents();
                $totals['yearlyCostCents'] += $operation->getYearlyCostCents();
            } else {
                $totals['monthlyIncomeCents'] += $operation->getMonthlyCostCents();
            }
        }

        return $totals;
    }

    private static function hasEnded(RecurringOperation $operation, string $day): bool
    {
        return null !== $operation->getEndsOn() && $operation->getEndsOn()->format('Y-m-d') < $day;
    }

    /** Whole calendar days from one Y-m-d to another: positive when the second is later. */
    private static function daysBetween(string $from, string $to): int
    {
        $utc = new \DateTimeZone('UTC');

        return (int) (new \DateTimeImmutable($from, $utc))->diff(new \DateTimeImmutable($to, $utc))->format('%r%a');
    }
}
