<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\CushionState;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Enum\ProjectDateMode;

/**
 * The projects laid out month by month over 36 months, capacity first and
 * cushion first (MAG-526, rules 3, 11, 12 and 13). Pure: everything it needs is
 * handed in, so the same inputs always give the same planning.
 *
 * Each month, in this order:
 *  1. the capacity is the net saving capacity less the payments of the
 *     projected loans already running (and, this month only, what was
 *     overspent), plus what a scenario's early repayments free and less what
 *     they cost — a cost the month cannot bear is paid off by the next ones
 *     (MAG-550);
 *  2. the cushion takes its share: all of it while building, the monthly
 *     recharge while recharging, until the deficit is covered;
 *  3. the projects with a fixed date take their even share, whatever is left
 *     (a month where they exceed it is `isOverCapacity`, the date is kept);
 *  4. what remains funds the first project not yet gathered, in priority order.
 *
 * A project is bought the month its salary part is gathered, never before the
 * projects it depends on, and — while the cushion is still being built — never
 * before the cushion is complete. A loan starts the month after the purchase.
 */
final class ProjectPlanner
{
    public const HORIZON_MONTHS = 36;

    public function __construct(private readonly LoanSchedule $loanSchedule)
    {
    }

    /**
     * @param list<Project>                                                      $projects     in priority order
     * @param array{state: string, deficitCents: int, monthlyRechargeCents: int} $cushion
     * @param int                                                                $reserveCents savings outside the cushion not already assigned to a source
     * @param array<int, int>                                                    $freedCents   month index → extra capacity (a payment a scenario's early repayment frees)
     * @param array<int, int>                                                    $outlayCents  month index → cash an early repayment takes; what the month cannot pay moves to the next
     *
     * @return array{
     *     startMonth: string,
     *     horizonMonths: int,
     *     netCapacityCents: int,
     *     isIncomeKnown: bool,
     *     cushion: array{state: string, completeMonth: string|null},
     *     months: list<array{month: string, capacityCents: int, cushionCents: int, salaryCents: int, savingsCents: int, capitalCents: int, loanPaymentsCents: int, isOverCapacity: bool}>,
     *     projects: list<array<string, mixed>>
     * }
     */
    public function plan(
        \DateTimeImmutable $today,
        int $netCapacityCents,
        bool $isIncomeKnown,
        array $cushion,
        int $reserveCents,
        array $projects,
        int $overspentCents = 0,
        array $freedCents = [],
        array $outlayCents = [],
    ): array {
        $horizon = self::HORIZON_MONTHS;
        $start = $today->modify('first day of this month')->setTime(0, 0);
        $net = $isIncomeKnown ? $netCapacityCents : 0;
        $count = \count($projects);

        $index = [];
        foreach ($projects as $i => $project) {
            $index[(string) $project->getId()] = $i;
        }

        // Rule 3: the reserve goes to the projects in priority order, today.
        $gathered = array_fill(0, $count, 0);
        $reserve = max(0, $reserveCents);
        foreach ($projects as $i => $project) {
            $gathered[$i] = min($project->getSalaryCents(), $reserve);
            $reserve -= $gathered[$i];
        }

        $left = [];
        $fixedAt = [];
        foreach ($projects as $i => $project) {
            $left[$i] = $project->getSalaryCents() - $gathered[$i];
            if (ProjectDateMode::Fixed === $project->getDateMode() && null !== $project->getTargetOn()) {
                $fixedAt[$i] = max(0, self::monthIndex($start, $project->getTargetOn()));
            }
        }
        $fixedNeed = $left;

        /** @var array<int, int|null> $purchase */
        $purchase = array_fill(0, $count, null);
        $firstSaving = array_fill(0, $count, null);
        /** @var array<int, array{startMonth: string, endMonth: string|null, monthlyPaymentCents: int}> $loans */
        $loans = [];
        $zeros = array_fill(0, $horizon, 0);
        [$loanPayments, $salary, $savings, $capital, $toCushion, $capacities] = [$zeros, $zeros, $zeros, $zeros, $zeros, $zeros];
        $savings[0] += array_sum($gathered);

        $deficit = max(0, $cushion['deficitCents']);
        $state = $cushion['state'];
        $waitsForCushion = CushionState::Building->value === $state && $deficit > 0;
        $completeAt = null;
        $pendingOutlay = 0;

        $buy = function (int $i, int $m) use ($projects, $start, $horizon, &$purchase, &$loans, &$loanPayments, &$savings, &$capital): void {
            $purchase[$i] = $m;
            foreach ($projects[$i]->getFundingSources() as $source) {
                match ($source->getKind()) {
                    FundingSourceKind::Savings => $savings[$m] += $source->getAmountCents(),
                    FundingSourceKind::Capital => $capital[$m] += $source->getAmountCents(),
                    FundingSourceKind::Loan => null,
                };
            }

            $loan = $projects[$i]->loanSource()?->getLoan();
            if (null === $loan) {
                return;
            }

            $rows = $this->rows($loan, $start->modify(\sprintf('+%d months', $m)));
            foreach ($rows as $j => $row) {
                if ($m + 1 + $j < $horizon) {
                    $loanPayments[$m + 1 + $j] += $row['paymentCents'];
                }
            }
            $last = [] === $rows ? null : $rows[array_key_last($rows)];
            $loans[$i] = [
                'startMonth' => $start->modify(\sprintf('+%d months', $m + 1))->format('Y-m'),
                'endMonth' => null !== $last && 0 === $last['remainingCents'] ? $last['month'] : null,
                'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            ];
        };

        for ($m = 0; $m < $horizon; ++$m) {
            foreach ($fixedAt as $i => $at) {
                if ($at === $m) {
                    $buy($i, $m);
                }
            }

            $available = max(0, $net + max(0, $freedCents[$m] ?? 0) - $loanPayments[$m] - (0 === $m ? max(0, $overspentCents) : 0));
            $pendingOutlay += max(0, $outlayCents[$m] ?? 0);
            $repaid = min($pendingOutlay, $available);
            $pendingOutlay -= $repaid;
            $available -= $repaid;
            if ($deficit > 0) {
                $toCushion[$m] = match ($state) {
                    CushionState::Building->value => min($available, $deficit),
                    CushionState::Recharging->value => min($available, $deficit, max(0, $cushion['monthlyRechargeCents'])),
                    default => 0,
                };
                $deficit -= $toCushion[$m];
                if (0 === $deficit) {
                    $completeAt = $m;
                }
            }
            $capacities[$m] = $available - $toCushion[$m];

            foreach ($fixedAt as $i => $at) {
                $share = self::evenShare($fixedNeed[$i], $at, $m);
                if ($share > 0) {
                    $salary[$m] += $share;
                    $firstSaving[$i] ??= $m;
                }
            }

            $free = max(0, $capacities[$m] - $salary[$m]);
            foreach (array_keys($projects) as $i) {
                if ($free <= 0) {
                    break;
                }
                if (isset($fixedAt[$i]) || $left[$i] <= 0) {
                    continue;
                }
                $give = min($free, $left[$i]);
                $left[$i] -= $give;
                $free -= $give;
                $salary[$m] += $give;
                $firstSaving[$i] ??= $m;
            }

            $open = !$waitsForCushion || null !== $completeAt;
            do {
                $bought = false;
                foreach ($projects as $i => $project) {
                    if (isset($fixedAt[$i]) || null !== $purchase[$i] || $left[$i] > 0 || !$open) {
                        continue;
                    }
                    if (!$this->dependenciesBought($project, $index, $purchase, $m)) {
                        continue;
                    }
                    $buy($i, $m);
                    $bought = true;
                }
            } while ($bought);
        }

        $months = [];
        for ($m = 0; $m < $horizon; ++$m) {
            $months[] = [
                'month' => self::label($start, $m),
                'capacityCents' => $capacities[$m],
                'cushionCents' => $toCushion[$m],
                'salaryCents' => $salary[$m],
                'savingsCents' => $savings[$m],
                'capitalCents' => $capital[$m],
                'loanPaymentsCents' => $loanPayments[$m],
                'isOverCapacity' => $salary[$m] > $capacities[$m],
            ];
        }

        $rows = [];
        foreach ($projects as $i => $project) {
            $rows[] = $this->projectRow($project, $i, $start, $purchase[$i], $firstSaving[$i], $gathered[$i], $loans[$i] ?? null);
        }

        return [
            'startMonth' => self::label($start, 0),
            'horizonMonths' => $horizon,
            'netCapacityCents' => $net,
            'isIncomeKnown' => $isIncomeKnown,
            'cushion' => [
                'state' => $state,
                'completeMonth' => null === $completeAt || CushionState::Complete->value === $state ? null : self::label($start, $completeAt),
            ],
            'months' => $months,
            'projects' => $rows,
        ];
    }

    /**
     * @param array<string, int>   $index
     * @param array<int, int|null> $purchase
     */
    private function dependenciesBought(Project $project, array $index, array $purchase, int $month): bool
    {
        foreach ($project->dependencyIds() as $id) {
            if (!isset($index[$id])) {
                continue;
            }
            $at = $purchase[$index[$id]];
            if (null === $at || $at > $month) {
                return false;
            }
        }

        return true;
    }

    /**
     * @param array{startMonth: string, endMonth: string|null, monthlyPaymentCents: int}|null $loan
     *
     * @return array<string, mixed>
     */
    private function projectRow(Project $project, int $i, \DateTimeImmutable $start, ?int $purchase, ?int $firstSaving, int $gathered, ?array $loan): array
    {
        $horizon = self::HORIZON_MONTHS;
        $deadline = ProjectDateMode::Deadline === $project->getDateMode() && null !== $project->getTargetOn()
            ? self::monthIndex($start, $project->getTargetOn())
            : null;

        $delay = 0;
        if (null !== $deadline) {
            // Past the horizon the purchase is at least that late: a lower bound, with isBeyondHorizon next to it.
            $delay = max(0, (null === $purchase ? $horizon : $purchase) - $deadline);
        }

        return [
            'projectId' => (string) $project->getId(),
            'name' => $project->getName(),
            'position' => $project->getPosition(),
            'wellbeing' => $project->getWellbeing()->value,
            'amountCents' => $project->getAmountCents(),
            'dateMode' => $project->getDateMode()->value,
            'targetOn' => $project->getTargetOn()?->format('Y-m-d'),
            'savingFromMonth' => self::label($start, $firstSaving ?? $purchase ?? $horizon - 1),
            'purchaseMonth' => null === $purchase ? null : self::label($start, $purchase),
            'gatheredCents' => $gathered,
            'loan' => $loan,
            'isBeyondHorizon' => null === $purchase,
            'isDeadlineMissed' => $delay > 0,
            'delayMonths' => $delay,
            'unmetDependencies' => $project->getUnmetDependencies(),
        ];
    }

    /**
     * A projected loan's payments, from the month after it is drawn: its
     * conditions only, never the first-payment day it may carry.
     *
     * @return list<array{month: string, paymentCents: int, interestCents: int, capitalCents: int, insuranceCents: int, remainingCents: int}>
     */
    private function rows(Loan $loan, \DateTimeImmutable $drawnIn): array
    {
        $schedule = new Loan();
        ProjectFundingRules::copy($loan, $schedule);
        $schedule->setFirstPaymentOn(null);

        return $this->loanSchedule->rows($schedule, $drawnIn);
    }

    /** `$cents` spread evenly over months 0 to `$last`, the remainder on the first ones. */
    private static function evenShare(int $cents, int $last, int $month): int
    {
        if ($month > $last || $cents <= 0) {
            return 0;
        }

        $months = $last + 1;

        return intdiv($cents, $months) + ($month < $cents % $months ? 1 : 0);
    }

    private static function monthIndex(\DateTimeImmutable $start, \DateTimeImmutable $day): int
    {
        return ((int) $day->format('Y') - (int) $start->format('Y')) * 12 + (int) $day->format('n') - (int) $start->format('n');
    }

    private static function label(\DateTimeImmutable $start, int $month): string
    {
        return $start->modify(\sprintf('+%d months', $month))->format('Y-m');
    }
}
