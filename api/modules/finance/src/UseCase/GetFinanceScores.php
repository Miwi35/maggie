<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Finance\Enum\DailyScore;
use Maggie\Finance\Enum\RhythmStatus;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurrenceSchedule;
use Symfony\Component\Uid\Ulid;

/**
 * Two scores, the week and the month, and the global one — the most degraded
 * of the month and the year (the annual envelopes). Each period carries its
 * spending rhythm.
 *
 * The rhythm is not linear. What is expected to be spent at a date is the
 * known debits already due (the recurring expenses of the month: rent,
 * energy, subscriptions, mostly at the start of it) plus the variable part of
 * the budget — the budget less those debits — spread evenly over the days.
 * The week only holds its own debits and its own days of the variable part.
 *
 * Colour, for the week and the month alike: red once the period's budget is
 * exceeded; otherwise it follows the rhythm — 95 % of the expected spending
 * or more is orange, under 80 % is green, in between is neutral. The month
 * keeps its own rules on top (an envelope overrun caused by mandatory transactions is red,
 * a planned overrun orange, and green still needs what MAG-274 asks of it).
 * A pure read: it publishes nothing and indexes nothing.
 */
class GetFinanceScores
{
    private const int ABOVE_RHYTHM_PERCENT = 95;
    private const int GREEN_BELOW_PERCENT = 80;

    public function __construct(
        private readonly GetDailyScore $getDailyScore,
        private readonly GetBudgetStatus $getBudgetStatus,
        private readonly CategoryRepository $categoryRepository,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly UserPreferenceRepository $preferenceRepository,
        private readonly RecurrenceSchedule $schedule,
    ) {
    }

    /**
     * The day asked for, or today in the user's timezone.
     *
     * @throws \InvalidArgumentException on anything but a real YYYY-MM-DD between 2000 and 2100
     */
    public function resolveDate(User $user, ?string $date): \DateTimeImmutable
    {
        if (null === $date || '' === $date) {
            $timezone = $this->preferenceRepository->findOneByUser($user)?->getTimezone() ?? 'Europe/Paris';

            return new \DateTimeImmutable(
                (new \DateTimeImmutable('now', new \DateTimeZone($timezone)))->format('Y-m-d'),
            );
        }

        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $date);
        $errors = \DateTimeImmutable::getLastErrors();
        if (false === $parsed || (false !== $errors && ($errors['warning_count'] > 0 || $errors['error_count'] > 0))) {
            throw new \InvalidArgumentException('date must be a valid YYYY-MM-DD day');
        }

        if ((int) $parsed->format('Y') < 2000 || (int) $parsed->format('Y') > 2100) {
            throw new \InvalidArgumentException('date must be between 2000 and 2100');
        }

        return $parsed;
    }

    /**
     * The day to read a month's scores at: today on the current month, else the
     * month's last day (past) or first day (future) — always a day inside the
     * month, so the month score is that month's.
     */
    public function dateInMonth(User $user, int $year, int $month): \DateTimeImmutable
    {
        $today = $this->resolveDate($user, null);
        $shown = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $current = $today->modify('first day of this month');

        return match (true) {
            $shown == $current => $today,
            $shown < $current => $shown->modify('last day of this month'),
            default => $shown,
        };
    }

    /** @return array<string, mixed> */
    public function execute(User $user, \DateTimeImmutable $date): array
    {
        $today = $date->format('Y-m-d');
        $operations = $this->operationRepository->findSeriesByUser($user);
        $childrenByParent = $this->childrenByParent($user);

        $monday = $date->modify('monday this week');
        $weekDays = [];
        for ($i = 0; $i < 7; ++$i) {
            $weekDays[] = $monday->modify(sprintf('+%d days', $i))->format('Y-m-d');
        }

        $plans = [];
        foreach ([...$weekDays, $today] as $day) {
            $key = substr($day, 0, 7);
            $plans[$key] ??= $this->plan($user, (int) substr($day, 0, 4), (int) substr($day, 5, 2), $operations, $childrenByParent);
        }

        $monthKey = substr($today, 0, 7);
        $monthPlan = $plans[$monthKey];
        $monthDays = [];
        for ($i = 1; $i <= $monthPlan['daysInMonth']; ++$i) {
            $monthDays[] = sprintf('%s-%02d', $monthKey, $i);
        }

        $month = $this->monthScore($user, $monthPlan, $this->rhythm($monthDays, $plans, $today, $monthPlan['budgetCents']));
        $week = $this->weekScore($plans, $this->rhythm($weekDays, $plans, $today, null));
        $year = $this->yearScore($monthPlan['annual']);

        $global = $month['status'];
        if (null !== $year['status'] && $year['status']->severity() > $global->severity()) {
            $global = $year['status'];
        }

        return [
            'date' => $today,
            'week' => $this->present($week),
            'month' => $this->present($month),
            'global' => [
                'status' => $global->value,
                'label' => $global->label(),
                'reasons' => [...$month['reasons'], ...$year['reasons']],
            ],
        ];
    }

    /**
     * Everything one month contributes: its budget, its debits, what its
     * envelopes consumed each day, and the annual envelopes covering it.
     *
     * @param list<\Maggie\Finance\Entity\RecurringOperation> $operations
     * @param array<string, list<string>>                     $childrenByParent
     *
     * @return array<string, mixed>
     */
    private function plan(User $user, int $year, int $month, array $operations, array $childrenByParent): array
    {
        $budget = $this->getBudgetStatus->execute($user, $year, $month);
        $start = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $next = $start->modify('+1 month');

        $categoryIds = [];
        foreach ($budget['budgets'] as $line) {
            if (null === $line['parentEnvelopeId']) {
                foreach ($this->selfAndDescendants($line['categoryId'], $childrenByParent) as $id) {
                    $categoryIds[$id] = true;
                }
            }
        }

        $debits = [];
        $debitsByDay = [];
        $debitsCents = 0;
        foreach ($operations as $operation) {
            if ($operation->getReferenceAmountCents() >= 0
                || $operation->getAccount()->isClosed()
                || !isset($categoryIds[(string) $operation->getCategory()->getId()])) {
                continue;
            }

            foreach ($this->schedule->occurrencesBetween($operation, $start, $next) as $occurrence) {
                $day = $occurrence->format('Y-m-d');
                $amount = abs($operation->getReferenceAmountCents());
                $debits[] = [
                    'date' => $day,
                    'label' => $operation->getLabel(),
                    'amountCents' => $amount,
                    'recurringOperationId' => (string) $operation->getId(),
                ];
                $debitsByDay[$day] = ($debitsByDay[$day] ?? 0) + $amount;
                $debitsCents += $amount;
            }
        }

        usort($debits, static fn (array $a, array $b): int => [$a['date'], $a['label']] <=> [$b['date'], $b['label']]);

        return [
            'year' => $year,
            'month' => $month,
            'daysInMonth' => (int) $start->format('t'),
            'budget' => $budget,
            'budgetCents' => $budget['totalBudgetedCents'],
            'variableCents' => max(0, $budget['totalBudgetedCents'] - $debitsCents),
            'debits' => $debits,
            'debitsByDay' => $debitsByDay,
            'spentByDay' => $this->transactionRepository->sumConsumedPerDayForCategoriesBetween(
                $user,
                array_map(static fn (string $id): Ulid => Ulid::fromString($id), array_keys($categoryIds)),
                $start,
                $next,
            ),
            'annual' => $budget['outsideMonthlyBudget']['annual'],
        ];
    }

    /**
     * The rhythm over a run of days, which may straddle two months.
     *
     * @param list<string>                        $days   Y-m-d, in order
     * @param array<string, array<string, mixed>> $plans  per Y-m
     * @param int|null                            $budget the period's budget, or null to take what its pace sums to
     *
     * @return array<string, mixed>
     */
    private function rhythm(array $days, array $plans, string $today, ?int $budget): array
    {
        $spentCumulative = 0;
        $debitsCumulative = 0;
        $counted = [];
        $curve = [];
        $debits = [];
        $spentToDate = 0;
        $expectedToDate = 0;
        $expected = 0;

        foreach ($days as $day) {
            $key = substr($day, 0, 7);
            $plan = $plans[$key];
            $counted[$key] = ($counted[$key] ?? 0) + 1;

            $debitsCumulative += $plan['debitsByDay'][$day] ?? 0;
            foreach ($plan['debits'] as $debit) {
                if ($debit['date'] === $day) {
                    $debits[] = $debit;
                }
            }

            $variable = 0.0;
            foreach ($counted as $countedKey => $n) {
                $variable += $plans[$countedKey]['variableCents'] * $n / $plans[$countedKey]['daysInMonth'];
            }
            $expected = (int) round($debitsCumulative + $variable);

            $isPast = $day <= $today;
            if ($isPast) {
                $spentCumulative += $plan['spentByDay'][$day] ?? 0;
            }

            $curve[] = [
                'date' => $day,
                'spentCents' => $isPast ? $spentCumulative : null,
                'expectedCents' => $expected,
            ];

            if ($day === $today) {
                $spentToDate = $spentCumulative;
                $expectedToDate = $expected;
            }
        }

        $budget ??= $expected;
        $status = $this->isAbove($spentToDate, $expectedToDate) ? RhythmStatus::Above : RhythmStatus::OnTrack;

        return [
            'budgetCents' => $budget,
            'spentCents' => $spentToDate,
            'expectedSpentCents' => $expectedToDate,
            'remainingCents' => $budget - $spentToDate,
            'expectedRemainingCents' => $budget - $expectedToDate,
            'status' => $status->value,
            'label' => $status->label(),
            'curve' => $curve,
            'debits' => $debits,
        ];
    }

    /**
     * @param array<string, mixed> $plan
     * @param array<string, mixed> $rhythm
     *
     * @return array{status: DailyScore, reasons: list<array<string, mixed>>, rhythm: array<string, mixed>}
     */
    private function monthScore(User $user, array $plan, array $rhythm): array
    {
        $daily = $this->getDailyScore->execute($user, $plan['year'], $plan['month']);
        $status = DailyScore::from($daily['score']);
        $reasons = $daily['reasons'];

        if ([] === $plan['budget']['budgets'] || DailyScore::Red === $status) {
            return ['status' => $status, 'reasons' => $reasons, 'rhythm' => $rhythm];
        }

        if ($this->isAbove($rhythm['spentCents'], $rhythm['expectedSpentCents'])) {
            $reasons[] = ['code' => 'above_rhythm', 'amountCents' => $rhythm['spentCents'] - $rhythm['expectedSpentCents']];
            if ($status->severity() < DailyScore::Orange->severity()) {
                $status = DailyScore::Orange;
            }
        } elseif (DailyScore::Green === $status && !$this->isBelowGreenLine($rhythm['spentCents'], $rhythm['expectedSpentCents'])) {
            $status = DailyScore::Neutral;
        }

        return ['status' => $status, 'reasons' => $reasons, 'rhythm' => $rhythm];
    }

    /**
     * The week is judged on its own budget — its days of the variable part
     * and the debits falling in it — and on its pace.
     *
     * @param array<string, array<string, mixed>> $plans
     * @param array<string, mixed>                $rhythm
     *
     * @return array{status: DailyScore, reasons: list<array<string, mixed>>, rhythm: array<string, mixed>}
     */
    private function weekScore(array $plans, array $rhythm): array
    {
        $hasBudget = false;
        foreach ($plans as $plan) {
            $hasBudget = $hasBudget || [] !== $plan['budget']['budgets'];
        }

        if (!$hasBudget) {
            return ['status' => DailyScore::Neutral, 'reasons' => [['code' => 'no_budget']], 'rhythm' => $rhythm];
        }

        $spent = $rhythm['spentCents'];
        $expected = $rhythm['expectedSpentCents'];

        if ($spent > $rhythm['budgetCents']) {
            return [
                'status' => DailyScore::Red,
                'reasons' => [['code' => 'week_budget_exceeded', 'amountCents' => $spent - $rhythm['budgetCents']]],
                'rhythm' => $rhythm,
            ];
        }

        if ($this->isAbove($spent, $expected)) {
            return [
                'status' => DailyScore::Orange,
                'reasons' => [['code' => 'above_rhythm', 'amountCents' => $spent - $expected]],
                'rhythm' => $rhythm,
            ];
        }

        if ($this->isBelowGreenLine($spent, $expected)) {
            return [
                'status' => DailyScore::Green,
                'reasons' => [['code' => 'below_rhythm', 'amountCents' => $expected - $spent]],
                'rhythm' => $rhythm,
            ];
        }

        return [
            'status' => DailyScore::Neutral,
            'reasons' => [['code' => 'on_rhythm', 'amountCents' => $expected - $spent]],
            'rhythm' => $rhythm,
        ];
    }

    /**
     * @param list<array<string, mixed>> $annualLines
     *
     * @return array{status: DailyScore|null, reasons: list<array<string, mixed>>} no status unless an annual envelope degrades it
     */
    private function yearScore(array $annualLines): array
    {
        $status = null;
        $reasons = [];

        foreach ($annualLines as $line) {
            if ($line['isOverspent']) {
                $status = DailyScore::Red;
                $reasons[] = [
                    'code' => 'annual_category_exceeded',
                    'categoryName' => $line['categoryName'],
                    'amountCents' => -$line['remainingCents'],
                ];
            } elseif ($line['isOvercommitted']) {
                $status ??= DailyScore::Orange;
                $reasons[] = [
                    'code' => 'plans_exceed_annual_category_budget',
                    'categoryName' => $line['categoryName'],
                    'amountCents' => -$line['availableCents'],
                ];
            }
        }

        return ['status' => $status, 'reasons' => $reasons];
    }

    /**
     * @param array{status: DailyScore, reasons: list<array<string, mixed>>, rhythm: array<string, mixed>} $score
     *
     * @return array<string, mixed>
     */
    private function present(array $score): array
    {
        return [
            'status' => $score['status']->value,
            'label' => $score['status']->label(),
            'reasons' => $score['reasons'],
            'rhythm' => $score['rhythm'],
        ];
    }

    private function isAbove(int $spentCents, int $expectedCents): bool
    {
        return $spentCents > 0 && $spentCents * 100 >= $expectedCents * self::ABOVE_RHYTHM_PERCENT;
    }

    private function isBelowGreenLine(int $spentCents, int $expectedCents): bool
    {
        return $spentCents * 100 < $expectedCents * self::GREEN_BELOW_PERCENT;
    }

    /** @return array<string, list<string>> */
    private function childrenByParent(User $user): array
    {
        $children = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            if (null !== $category->getParent()) {
                $children[(string) $category->getParent()->getId()][] = (string) $category->getId();
            }
        }

        return $children;
    }

    /**
     * @param array<string, list<string>> $childrenByParent
     *
     * @return list<string>
     */
    private function selfAndDescendants(string $categoryId, array $childrenByParent): array
    {
        $ids = [$categoryId];
        $queue = [$categoryId];
        while ([] !== $queue) {
            foreach ($childrenByParent[array_shift($queue)] ?? [] as $childId) {
                $ids[] = $childId;
                $queue[] = $childId;
            }
        }

        return $ids;
    }
}
