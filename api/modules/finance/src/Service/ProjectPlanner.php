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
 * cushion first (MAG-526, rules 3, 11, 12 and 13; rule 3 revised by MAG-669). Pure: everything it needs is
 * handed in, so the same inputs always give the same planning.
 *
 * Each month, in this order:
 *  1. the capacity is the net saving capacity less the payments of the
 *     projected loans already running and the deposits of a scenario's
 *     placements (MAG-556) (and, this month only, what was overspent), plus
 *     what a scenario's early repayments free and less what they cost — a cost
 *     the month cannot bear is paid off by the next ones (MAG-550);
 *  2. the cushion takes its share: all of it while building, the monthly
 *     recharge while recharging, until the deficit is covered;
 *  3. the projects with a fixed date take their even share, whatever is left
 *     (a month where they exceed it is `isOverCapacity`, the date is kept);
 *  4. what remains funds the first project not yet gathered, in priority order.
 *
 * A project is bought the month its salary part is gathered, never before the
 * projects it depends on, and never while the cushion takes the whole capacity:
 * below its critical threshold, or short of its target with a 100 % share. A
 * loan starts the month after the purchase.
 *
 * An investment (MAG-557) brings its rent from the month after the purchase and
 * is resold the planned month, never before the one after the purchase: the
 * loan and the rent stop then, and the net proceeds either land on the project
 * they were set aside for — which is not bought before they do — or, with no
 * target, go to the projects waiting, in priority order, that month.
 */
final class ProjectPlanner
{
    public const HORIZON_MONTHS = 36;

    public function __construct(private readonly LoanSchedule $loanSchedule)
    {
    }

    /**
     * @param list<Project>                                                                         $projects     in priority order
     * @param array{state: string, deficitCents: int, criticalDeficitCents: int, sharePercent: int} $cushion      criticalDeficitCents: what is missing to the critical threshold; sharePercent: the cushion's part past it
     * @param int                                                                                   $reserveCents savings outside the cushion not already assigned to a source
     * @param array<int, int>                                                                       $freedCents   month index → extra capacity (a payment a scenario's early repayment frees)
     * @param array<int, int>                                                                       $outlayCents  month index → cash an early repayment takes; what the month cannot pay moves to the next
     * @param array<int, int>                                                                       $depositCents month index → what a scenario's placements take of the capacity (their deposits, per month)
     *
     * @return array{
     *     startMonth: string,
     *     horizonMonths: int,
     *     netCapacityCents: int,
     *     isIncomeKnown: bool,
     *     cushion: array{state: string, completeMonth: string|null},
     *     months: list<array{month: string, capacityCents: int, cushionCents: int, salaryCents: int, savingsCents: int, capitalCents: int, loanPaymentsCents: int, rentCents: int, resaleCents: int, isOverCapacity: bool}>,
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
        array $depositCents = [],
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

        // The month each investment's resale is planned for, and the one it really happens in once bought.
        $resalePlanned = [];
        foreach ($projects as $i => $project) {
            $investment = $project->getInvestment();
            if (null !== $investment && null !== $investment->getResaleOn()) {
                $resalePlanned[$i] = max(0, self::monthIndex($start, $investment->getResaleOn()));
            }
        }
        /** @var array<int, int|null> $resaleAt */
        $resaleAt = array_fill(0, $count, null);

        /** @var array<int, int|null> $purchase */
        $purchase = array_fill(0, $count, null);
        $firstSaving = array_fill(0, $count, null);
        /** @var array<int, array{startMonth: string, endMonth: string|null, monthlyPaymentCents: int}> $loans */
        $loans = [];
        $zeros = array_fill(0, $horizon, 0);
        [$loanPayments, $salary, $savings, $capital, $toCushion, $capacities, $rent, $resaleIn, $windfall] = [$zeros, $zeros, $zeros, $zeros, $zeros, $zeros, $zeros, $zeros, $zeros];
        $savings[0] += array_sum($gathered);

        $deficit = max(0, $cushion['deficitCents']);
        $critical = min($deficit, max(0, $cushion['criticalDeficitCents']));
        $cushionShare = max(0, min(100, $cushion['sharePercent']));
        $state = $cushion['state'];
        $completeAt = null;
        $pendingOutlay = 0;

        $buy = function (int $i, int $m) use ($projects, $start, $horizon, $resalePlanned, &$resaleAt, &$purchase, &$loans, &$loanPayments, &$savings, &$capital, &$rent, &$resaleIn, &$windfall): void {
            $purchase[$i] = $m;
            foreach ($projects[$i]->getFundingSources() as $source) {
                match ($source->getKind()) {
                    FundingSourceKind::Savings => $savings[$m] += $source->getAmountCents(),
                    FundingSourceKind::Capital => $capital[$m] += $source->getAmountCents(),
                    // Already taken off the salary part; the purchase only waits for it to land.
                    FundingSourceKind::Loan, FundingSourceKind::Resale => null,
                };
            }

            $investment = $projects[$i]->getInvestment();
            // Sold at the planned month, the one after the purchase at the earliest.
            $soldAt = isset($resalePlanned[$i]) ? max($resalePlanned[$i], $m + 1) : null;
            if (null !== $soldAt && null !== $investment) {
                $resaleAt[$i] = $soldAt;
                if ($soldAt < $horizon) {
                    $resaleIn[$soldAt] += (int) $investment->getResaleNetCents();
                    // What the target does not take is cash of the month, like a resale with no target.
                    $taken = 0;
                    foreach ($investment->getResaleTarget()?->getFundingSources() ?? [] as $source) {
                        if (FundingSourceKind::Resale === $source->getKind() && $source->getSourceProjectId() === (string) $projects[$i]->getId()) {
                            $taken += $source->getAmountCents();
                        }
                    }
                    $windfall[$soldAt] += max(0, (int) $investment->getResaleNetCents() - $taken);
                }
            }
            $heldUntil = $soldAt ?? $horizon;

            if (null !== $investment) {
                for ($mm = $m + 1; $mm < min($heldUntil, $horizon); ++$mm) {
                    $rent[$mm] += $investment->getMonthlyRentCents();
                }
            }

            $loan = $projects[$i]->loanSource()?->getLoan();
            if (null === $loan) {
                return;
            }

            $rows = $this->rows($loan, $start->modify(\sprintf('+%d months', $m)));
            $paid = [];
            foreach ($rows as $j => $row) {
                $at = $m + 1 + $j;
                if ($at < $horizon && $at < $heldUntil) {
                    $loanPayments[$at] += $row['paymentCents'];
                }
                if ($at < $heldUntil) {
                    $paid[] = $row;
                }
            }
            $last = [] === $paid ? null : $paid[array_key_last($paid)];
            $repaid = null !== $last && 0 === $last['remainingCents'];
            $loans[$i] = [
                'startMonth' => $start->modify(\sprintf('+%d months', $m + 1))->format('Y-m'),
                // The last month paid: the loan's end, or the month before the resale clears it.
                'endMonth' => $repaid ? $last['month'] : (null !== $soldAt ? $start->modify(\sprintf('+%d months', max($m + 1, $soldAt - 1)))->format('Y-m') : null),
                'monthlyPaymentCents' => $loan->getMonthlyPaymentCents(),
            ];
        };

        for ($m = 0; $m < $horizon; ++$m) {
            foreach ($fixedAt as $i => $at) {
                if ($at === $m) {
                    $buy($i, $m);
                }
            }

            $available = max(0, $net + max(0, $freedCents[$m] ?? 0) + $rent[$m] - $loanPayments[$m] - max(0, $depositCents[$m] ?? 0) - (0 === $m ? max(0, $overspentCents) : 0));
            $pendingOutlay += max(0, $outlayCents[$m] ?? 0);
            $repaid = min($pendingOutlay, $available);
            $pendingOutlay -= $repaid;
            $available -= $repaid;
            if ($deficit > 0) {
                // Below the critical threshold the cushion takes everything; past it, its share.
                $toCushion[$m] = $critical > 0
                    ? min($available, $deficit)
                    : min(intdiv($available * $cushionShare, 100), $deficit);
                $deficit -= $toCushion[$m];
                $critical = max(0, $critical - $toCushion[$m]);
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

            // A resale with no target is cash of the month: it funds the first waiting project before the salary does.
            $pool = $windfall[$m];
            $free = max(0, $capacities[$m] - $salary[$m]);
            foreach (array_keys($projects) as $i) {
                if ($free <= 0 && $pool <= 0) {
                    break;
                }
                if (isset($fixedAt[$i]) || $left[$i] <= 0) {
                    continue;
                }
                $fromResale = min($pool, $left[$i]);
                $fromSalary = min($free, $left[$i] - $fromResale);
                $pool -= $fromResale;
                $free -= $fromSalary;
                $left[$i] -= $fromResale + $fromSalary;
                $salary[$m] += $fromSalary;
                if ($fromResale + $fromSalary > 0) {
                    $firstSaving[$i] ??= $m;
                }
            }

            $open = 0 === $deficit || (0 === $critical && $cushionShare < 100);
            do {
                $bought = false;
                foreach ($projects as $i => $project) {
                    if (isset($fixedAt[$i]) || null !== $purchase[$i] || $left[$i] > 0 || !$open) {
                        continue;
                    }
                    if (!$this->dependenciesBought($project, $index, $purchase, $m) || !self::resalesLanded($project, $index, $resaleAt, $m)) {
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
                'rentCents' => $rent[$m],
                'resaleCents' => $resaleIn[$m],
                'isOverCapacity' => $salary[$m] > $capacities[$m],
            ];
        }

        $rows = [];
        foreach ($projects as $i => $project) {
            $rows[] = $this->projectRow($project, $i, $start, $purchase[$i], $firstSaving[$i], $gathered[$i], $loans[$i] ?? null, $resaleAt[$i], self::resalesLandAt($project, $index, $resaleAt));
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
     * A project funded by the resale of another is not bought before the money
     * lands: the investment must be bought, sold, and the month come.
     *
     * @param array<string, int>   $index
     * @param array<int, int|null> $resaleAt
     */
    private static function resalesLanded(Project $project, array $index, array $resaleAt, int $month): bool
    {
        foreach ($project->getFundingSources() as $source) {
            $id = $source->getSourceProjectId();
            if (FundingSourceKind::Resale !== $source->getKind() || null === $id || !isset($index[$id])) {
                continue;
            }
            $at = $resaleAt[$index[$id]];
            if (null === $at || $at > $month) {
                return false;
            }
        }

        return true;
    }

    /**
     * The month the last of a project's resale sources lands, null when it has
     * none or one of them is not sold within the planning yet.
     *
     * @param array<string, int>   $index
     * @param array<int, int|null> $resaleAt
     */
    private static function resalesLandAt(Project $project, array $index, array $resaleAt): ?int
    {
        $last = null;
        foreach ($project->getFundingSources() as $source) {
            $id = $source->getSourceProjectId();
            if (FundingSourceKind::Resale !== $source->getKind() || null === $id || !isset($index[$id])) {
                continue;
            }
            $at = $resaleAt[$index[$id]];
            if (null === $at) {
                return null;
            }
            $last = max($last ?? $at, $at);
        }

        return $last;
    }

    /**
     * @param array{startMonth: string, endMonth: string|null, monthlyPaymentCents: int}|null $loan
     *
     * @return array<string, mixed>
     */
    private function projectRow(Project $project, int $i, \DateTimeImmutable $start, ?int $purchase, ?int $firstSaving, int $gathered, ?array $loan, ?int $soldAt, ?int $resaleLandsAt): array
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
            'investment' => $this->investmentRow($project, $start, $purchase, $soldAt),
            'resaleFromMonth' => null === $resaleLandsAt ? null : self::label($start, $resaleLandsAt),
            'isBeyondHorizon' => null === $purchase,
            'isDeadlineMissed' => $delay > 0,
            'delayMonths' => $delay,
            'unmetDependencies' => $project->getUnmetDependencies(),
        ];
    }

    /**
     * What the investment brings on the Gantt: the rent from the month after
     * the purchase until the resale, the resale and where its proceeds go.
     * Null for a project that is not an investment; the dates are null while
     * the purchase or the resale falls beyond the planning.
     *
     * @return array<string, mixed>|null
     */
    private function investmentRow(Project $project, \DateTimeImmutable $start, ?int $purchase, ?int $soldAt): ?array
    {
        $investment = $project->getInvestment();
        if (null === $investment) {
            return null;
        }

        $horizon = self::HORIZON_MONTHS;
        $resale = $investment->getResale();
        $isSold = null !== $soldAt && $soldAt < $horizon;

        return [
            'monthlyRentCents' => $investment->getMonthlyRentCents(),
            'monthlyBalanceCents' => $investment->getMonthlyBalanceCents(),
            'rentFromMonth' => null === $purchase || $purchase + 1 >= $horizon ? null : self::label($start, $purchase + 1),
            // Null: still rented at the end of the planning.
            'rentUntilMonth' => $isSold ? self::label($start, max(($purchase ?? 0) + 1, $soldAt - 1)) : null,
            'resaleMonth' => $isSold ? self::label($start, $soldAt) : null,
            'resaleNetCents' => $resale['netAmountCents'] ?? null,
            'resaleTargetProjectId' => $resale['targetProjectId'] ?? null,
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
