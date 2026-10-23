<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\PartialRepaymentEffect;
use Maggie\Finance\UseCase\SimulateEarlyRepayment;
use Symfony\Component\Uid\Ulid;

/**
 * The early repayments of a scenario, simulated over the signed loans
 * (MAG-550, decision D4). Pure: no write, the loans are only read.
 *
 * A repayment `{id, loanId, month, amountCents|null, effect|null}` (null amount
 * repays the whole capital; `effect` `duration` or `payment` says what a partial
 * one shortens, null follows the loan's offer) happens in `month`, M0 being the
 * current one:
 *  - it costs its amount plus the penalty that month ({@see ProjectPlanner}
 *    carries what the month cannot bear to the next ones);
 *  - the loan is rescheduled from the next month — shorter, or with a lower
 *    payment when the offer says so — and the payments it no longer asks are
 *    freed, month by month, up to the loan's natural end: the base capacity
 *    never credited that end, so nothing is counted twice.
 */
final class ScenarioRepayments
{
    public const MAX_REPAYMENTS = 50;
    public const MAX_ID_LENGTH = 64;
    public const EFFECTS = ['duration' => PartialRepaymentEffect::Shorten, 'payment' => PartialRepaymentEffect::LowerPayment];

    public function __construct(
        private readonly LoanSchedule $loanSchedule,
        private readonly PrepaymentPenalty $prepaymentPenalty,
        private readonly SimulateEarlyRepayment $simulateEarlyRepayment,
    ) {
    }

    /**
     * @param array<mixed> $raw
     *
     * @return array{id: string, loanId: string, month: string, amountCents: int|null, effect: string|null}
     *
     * @throws \DomainException when the shape is wrong
     */
    public function normalize(array $raw): array
    {
        $loanId = $raw['loanId'] ?? null;
        if (!\is_string($loanId) || '' === $loanId) {
            throw new \DomainException('A repayment needs a loanId.');
        }

        $month = $raw['month'] ?? null;
        if (!\is_string($month) || 1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
            throw new \DomainException('A repayment needs a month, YYYY-MM.');
        }

        $amount = $raw['amountCents'] ?? null;
        if (null !== $amount && (!\is_int($amount) || $amount <= 0)) {
            throw new \DomainException('amountCents must be a positive integer of cents, or null to repay the whole capital.');
        }

        $effect = $raw['effect'] ?? null;
        if (null !== $effect && (!\is_string($effect) || !isset(self::EFFECTS[$effect]))) {
            throw new \DomainException("A repayment's effect is 'duration' (shorter loan), 'payment' (lower payment) or null (the loan's offer).");
        }

        $id = $raw['id'] ?? null;
        if (null !== $id && (!\is_string($id) || '' === $id || \strlen($id) > self::MAX_ID_LENGTH)) {
            throw new \DomainException(\sprintf('A repayment id must be a non-empty string of %d characters at most.', self::MAX_ID_LENGTH));
        }

        return ['id' => $id ?? (string) new Ulid(), 'loanId' => $loanId, 'month' => $month, 'amountCents' => $amount, 'effect' => $effect];
    }

    /**
     * @param list<Loan>                 $loans      the signed loans
     * @param list<array<string, mixed>> $repayments normalized
     * @param bool                       $allLoans   also schedule the loans without a repayment (interest, end date)
     * @param bool                       $strict     throw on a repayment the loans do not allow, else skip it
     *
     * @return array{
     *     freed: array<int, int>,
     *     outlay: array<int, int>,
     *     interestPaidCents: int,
     *     loans: list<array{loanId: string, endMonth: string|null, interestCents: int, interestSavedCents: int}>,
     *     repayments: list<array{id: string, loanId: string, loanName: string, month: string, amountCents: int, immobilizedCents: int, effect: string|null, penaltyCents: int, interestSavedCents: int, realGainCents: int, freedMonthlyCents: int}>
     * }
     *
     * @throws \DomainException when `$strict` and a repayment is not possible
     */
    public function apply(array $loans, array $repayments, \DateTimeImmutable $start, bool $allLoans, bool $strict): array
    {
        $byLoan = [];
        foreach ($repayments as $repayment) {
            $byLoan[$repayment['loanId']][] = $repayment;
        }

        $known = [];
        foreach ($loans as $loan) {
            $known[(string) $loan->getId()] = true;
        }
        $unknown = array_keys(array_diff_key($byLoan, $known));
        if ($strict && [] !== $unknown) {
            throw new \DomainException('Loan not found: '.implode(', ', $unknown));
        }

        $freed = [];
        $outlay = [];
        $interestPaid = 0;
        $loanRows = [];
        $done = [];

        foreach ($loans as $loan) {
            $loanId = (string) $loan->getId();
            if (!$allLoans && !isset($byLoan[$loanId])) {
                continue;
            }

            $original = self::byMonth($this->loanSchedule->rows($loan, $start));
            $current = $original;
            $penalties = 0;

            $mine = $byLoan[$loanId] ?? [];
            usort($mine, static fn (array $a, array $b): int => $a['month'] <=> $b['month']);

            $months = [];
            foreach ($mine as $repayment) {
                try {
                    if (isset($months[$repayment['month']])) {
                        throw new \DomainException("The loan {$loan->getName()} already has a repayment in {$repayment['month']}: give one, with the total amount.");
                    }
                    $months[$repayment['month']] = true;
                    $result = $this->repay($loan, $repayment, $start, $current);
                } catch (\DomainException $e) {
                    if ($strict) {
                        throw $e;
                    }

                    continue;
                }

                $current = $result['schedule'];
                $penalties += $result['penaltyCents'];
                $index = $result['index'];
                $outlay[$index] = ($outlay[$index] ?? 0) + $result['amountCents'] + $result['penaltyCents'];
                $done[$repayment['id']] = [
                    'id' => $repayment['id'],
                    'loanId' => $loanId,
                    'loanName' => $loan->getName(),
                    'month' => $repayment['month'],
                    'amountCents' => $result['amountCents'],
                    'immobilizedCents' => $result['amountCents'],
                    'effect' => $result['effect'],
                    'penaltyCents' => $result['penaltyCents'],
                    'interestSavedCents' => $result['interestSavedCents'],
                    'realGainCents' => SimulateEarlyRepayment::realGainCents($result['interestSavedCents'], $result['penaltyCents']),
                    'freedMonthlyCents' => 0,
                ];
            }

            foreach ($original as $index => $row) {
                $gap = $row['paymentCents'] - ($current[$index]['paymentCents'] ?? 0);
                if (0 !== $gap) {
                    $freed[$index] = ($freed[$index] ?? 0) + $gap;
                }
            }

            $interest = array_sum(array_column($current, 'interestCents'));
            $interestPaid += $interest + $penalties;
            $last = [] === $current ? null : $current[array_key_last($current)];
            $loanRows[] = [
                'loanId' => $loanId,
                'endMonth' => null !== $last && 0 === $last['remainingCents'] ? $last['month'] : null,
                'interestCents' => $interest,
                'interestSavedCents' => array_sum(array_column($original, 'interestCents')) - $interest,
            ];
        }

        // What the loan asks less in the first month it asks less after the repayment:
        // from the next month when the payment falls, from the new end when the term does.
        ksort($freed);
        foreach ($done as $id => $row) {
            $index = self::monthIndex($start, $row['month']);
            foreach ($freed as $month => $cents) {
                if ($month > $index && $cents > 0) {
                    $done[$id]['freedMonthlyCents'] = $cents;
                    break;
                }
            }
        }

        return [
            'freed' => $freed,
            'outlay' => $outlay,
            'interestPaidCents' => $interestPaid,
            'loans' => $loanRows,
            'repayments' => array_values(array_filter(array_map(
                static fn (array $repayment): ?array => $done[$repayment['id']] ?? null,
                $repayments,
            ))),
        ];
    }

    /**
     * @param array{id: string, loanId: string, month: string, amountCents: int|null, effect?: string|null}                                        $repayment
     * @param array<int, array{month: string, paymentCents: int, interestCents: int, capitalCents: int, insuranceCents: int, remainingCents: int}> $schedule
     *
     * @return array{schedule: array<int, array<string, mixed>>, index: int, amountCents: int, effect: string|null, penaltyCents: int, interestSavedCents: int}
     */
    private function repay(Loan $loan, array $repayment, \DateTimeImmutable $start, array $schedule): array
    {
        $index = self::monthIndex($start, $repayment['month']);
        if ($index < 0 || $index >= ProjectPlanner::HORIZON_MONTHS) {
            throw new \DomainException(\sprintf('A repayment falls within the %d months of the planning, from %s.', ProjectPlanner::HORIZON_MONTHS, $start->format('Y-m')));
        }
        if (!$loan->isEarlyRepaymentAllowed()) {
            throw new \DomainException("The loan {$loan->getName()} does not allow an early repayment.");
        }

        $owed = 0 === $index ? $loan->getPrincipalRemainingCents() : ($schedule[$index]['remainingCents'] ?? 0);
        if ($owed <= 0) {
            throw new \DomainException("The loan {$loan->getName()} is already repaid in {$repayment['month']}.");
        }

        $amount = $repayment['amountCents'] ?? $owed;
        if ($amount > $owed) {
            throw new \DomainException(\sprintf('amountCents must be between 1 and the capital owed in %s, %d.', $repayment['month'], $owed));
        }
        $minimum = $loan->getMinPartialRepaymentCents();
        if ($amount < $owed && null !== $minimum && $amount < $minimum) {
            throw new \DomainException(\sprintf('The lender takes no partial repayment under %d cents.', $minimum));
        }

        $effect = null === ($repayment['effect'] ?? null) ? null : self::EFFECTS[$repayment['effect']];
        if (PartialRepaymentEffect::LowerPayment === $effect && $amount < $owed && !SimulateEarlyRepayment::isAmortizing($loan)) {
            throw new \DomainException("The loan {$loan->getName()} is not amortizing: a partial repayment can only shorten it.");
        }

        $left = array_filter($schedule, static fn (int $i): bool => $i > $index, \ARRAY_FILTER_USE_KEY);
        $monthsLeft = \count($left);
        $penalty = $this->prepaymentPenalty->penaltyCents($loan, $amount, $monthsLeft);
        $interestBefore = array_sum(array_column($left, 'interestCents'));

        $rescheduled = [];
        $lowered = false;
        if ($amount < $owed) {
            $after = $owed - $amount;
            $paymentAfter = $this->simulateEarlyRepayment->paymentAfter($loan, $after, $monthsLeft, $effect);
            $lowered = null !== $paymentAfter;
            $rescheduled = $this->loanSchedule->rows(
                $loan,
                $start->modify(\sprintf('+%d months', $index)),
                principalCents: $after,
                monthlyPaymentCents: $paymentAfter,
            );
        }

        $next = array_filter($schedule, static fn (int $i): bool => $i <= $index, \ARRAY_FILTER_USE_KEY);
        foreach ($rescheduled as $j => $row) {
            $next[$index + 1 + $j] = $row;
        }

        return [
            'schedule' => $next,
            'index' => $index,
            'amountCents' => $amount,
            'effect' => $amount >= $owed ? null : ($lowered ? 'payment' : 'duration'),
            'penaltyCents' => $penalty,
            'interestSavedCents' => $interestBefore - array_sum(array_column($rescheduled, 'interestCents')),
        ];
    }

    /**
     * @param list<array<string, mixed>> $rows from next month
     *
     * @return array<int, array<string, mixed>> keyed by month index, the current month being 0
     */
    private static function byMonth(array $rows): array
    {
        $byMonth = [];
        foreach ($rows as $j => $row) {
            $byMonth[$j + 1] = $row;
        }

        return $byMonth;
    }

    private static function monthIndex(\DateTimeImmutable $start, string $month): int
    {
        [$year, $number] = array_map('intval', explode('-', $month));

        return ($year - (int) $start->format('Y')) * 12 + $number - (int) $start->format('n');
    }
}
