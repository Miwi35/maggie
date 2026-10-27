<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Finance\Enum\DailyScore;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\RecurrenceSchedule;

/**
 * Two scores, the week and the month, and the global one — the most degraded
 * of the month and the year (the annual envelopes). Each period carries its
 * spending rhythm: two curves to read, never a verdict (MAG-643).
 *
 * « Dépensé » is every real debit of the period, classified or not, with or
 * without an envelope — the same definition as the period balance
 * ({@see TransactionRepository::sumConsumedBetween()}). « Attendu » is what was
 * planned ahead: the recurring debits of the period, whatever their category.
 * Envelopes sort where the money goes; they play no part in the rhythm, and
 * spending more than expected is normal: no colour, no word is drawn from the
 * gap. The rhythm carries the debits nobody classified (`unclassifiedCount`,
 * `unclassifiedCents`) as an invitation to classify them, not as an exclusion.
 *
 * The month's colour comes from its envelopes alone (an overrun caused by
 * mandatory transactions is red, a planned overrun orange, and green still
 * needs what MAG-274 asks of it) and the annual ones; the week has none.
 * A pure read: it publishes nothing and indexes nothing.
 */
class GetFinanceScores
{
    private const array OUTSIDE_LIFESTYLE = [QualificationKind::Repayment, QualificationKind::Saving, QualificationKind::Investment];

    public function __construct(
        private readonly GetDailyScore $getDailyScore,
        private readonly GetBudgetStatus $getBudgetStatus,
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

        $monday = $date->modify('monday this week');
        $weekDays = [];
        for ($i = 0; $i < 7; ++$i) {
            $weekDays[] = $monday->modify(sprintf('+%d days', $i))->format('Y-m-d');
        }

        $plans = [];
        foreach ([...$weekDays, $today] as $day) {
            $key = substr($day, 0, 7);
            $plans[$key] ??= $this->plan($user, (int) substr($day, 0, 4), (int) substr($day, 5, 2), $operations);
        }

        $monthKey = substr($today, 0, 7);
        $monthPlan = $plans[$monthKey];
        $monthDays = [];
        for ($i = 1; $i <= $monthPlan['daysInMonth']; ++$i) {
            $monthDays[] = sprintf('%s-%02d', $monthKey, $i);
        }

        $tomorrow = $date->modify('+1 day');
        $monthUnclassified = $this->transactionRepository->summariseUnclassifiedDebitsBetween($user, new \DateTimeImmutable($monthKey.'-01'), $tomorrow);
        $weekUnclassified = $this->transactionRepository->summariseUnclassifiedDebitsBetween($user, $monday, $tomorrow);

        $month = $this->monthScore($user, $monthPlan, $this->rhythm($monthDays, $plans, $today, $monthUnclassified));
        $week = $this->weekScore($this->rhythm($weekDays, $plans, $today, $weekUnclassified));
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
     * Everything one month contributes: its envelopes (for the score), its
     * planned recurring debits — every category — what was really spent each
     * day, and the annual envelopes covering it.
     *
     * @param list<\Maggie\Finance\Entity\RecurringOperation> $operations
     *
     * @return array<string, mixed>
     */
    private function plan(User $user, int $year, int $month, array $operations): array
    {
        $budget = $this->getBudgetStatus->execute($user, $year, $month);
        $start = new \DateTimeImmutable(sprintf('%04d-%02d-01', $year, $month));
        $next = $start->modify('+1 month');

        $debits = [];
        $debitsByDay = [];
        foreach ($operations as $operation) {
            // What « Dépensé » leaves out is not expected either: saving and repayments.
            if ($operation->getReferenceAmountCents() >= 0
                || $operation->getAccount()->isClosed()
                || \in_array($operation->getQualification()->getKind(), self::OUTSIDE_LIFESTYLE, true)) {
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
            }
        }

        usort($debits, static fn (array $a, array $b): int => [$a['date'], $a['label']] <=> [$b['date'], $b['label']]);

        return [
            'year' => $year,
            'month' => $month,
            'daysInMonth' => (int) $start->format('t'),
            'budget' => $budget,
            'debits' => $debits,
            'debitsByDay' => $debitsByDay,
            'spentByDay' => $this->transactionRepository->sumConsumedPerDayBetween($user, $start, $next),
            'annual' => $budget['outsideMonthlyBudget']['annual'],
        ];
    }

    /**
     * The rhythm over a run of days, which may straddle two months: what was
     * spent and what was planned, both cumulated day by day.
     *
     * @param list<string>                        $days         Y-m-d, in order
     * @param array<string, array<string, mixed>> $plans        per Y-m
     * @param array{count: int, cents: int}       $unclassified the period's debits no category claims, up to today
     *
     * @return array<string, mixed>
     */
    private function rhythm(array $days, array $plans, string $today, array $unclassified): array
    {
        $spentCumulative = 0;
        $expectedCumulative = 0;
        $curve = [];
        $debits = [];
        $spentToDate = 0;
        $expectedToDate = 0;

        foreach ($days as $day) {
            $plan = $plans[substr($day, 0, 7)];

            $expectedCumulative += $plan['debitsByDay'][$day] ?? 0;
            foreach ($plan['debits'] as $debit) {
                if ($debit['date'] === $day) {
                    $debits[] = $debit;
                }
            }

            $isPast = $day <= $today;
            if ($isPast) {
                $spentCumulative += $plan['spentByDay'][$day] ?? 0;
            }

            $curve[] = [
                'date' => $day,
                'spentCents' => $isPast ? $spentCumulative : null,
                'expectedCents' => $expectedCumulative,
            ];

            if ($day === $today) {
                $spentToDate = $spentCumulative;
                $expectedToDate = $expectedCumulative;
            }
        }

        return [
            'spentCents' => $spentToDate,
            'expectedSpentCents' => $expectedToDate,
            'plannedCents' => $expectedCumulative,
            'unclassifiedCount' => $unclassified['count'],
            'unclassifiedCents' => $unclassified['cents'],
            'curve' => $curve,
            'debits' => $debits,
        ];
    }

    /**
     * The month is judged on its envelopes alone: the rhythm is shown, not graded.
     *
     * @param array<string, mixed> $plan
     * @param array<string, mixed> $rhythm
     *
     * @return array{status: DailyScore, reasons: list<array<string, mixed>>, rhythm: array<string, mixed>}
     */
    private function monthScore(User $user, array $plan, array $rhythm): array
    {
        $daily = $this->getDailyScore->execute($user, $plan['year'], $plan['month']);

        return ['status' => DailyScore::from($daily['score']), 'reasons' => $daily['reasons'], 'rhythm' => $rhythm];
    }

    /**
     * The week has no envelope of its own to be judged on: neutral, with
     * its rhythm to read.
     *
     * @param array<string, mixed> $rhythm
     *
     * @return array{status: DailyScore, reasons: list<array<string, mixed>>, rhythm: array<string, mixed>}
     */
    private function weekScore(array $rhythm): array
    {
        return ['status' => DailyScore::Neutral, 'reasons' => [], 'rhythm' => $rhythm];
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
}
