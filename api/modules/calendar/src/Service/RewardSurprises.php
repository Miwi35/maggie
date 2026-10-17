<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\RewardRepository;
use Maggie\Calendar\Repository\TaskRepository;

/**
 * The « étoile filante » (MAG-508): a rare surprise, decided by rules and not
 * by chance, one per local day at most. By priority:
 *
 * 1. `fullDay` — this task makes every slot of the day done (3 slots at least);
 * 2. `monthRecord` — the stars of the month pass, for the first time this
 *    month, the best of the months before (no record the first month; one
 *    held back by the day's other surprise comes with the next star);
 * 3. `waited` — the task had been put off for 3 days or more.
 */
final class RewardSurprises
{
    public const FULL_DAY_MIN_SLOTS = 3;
    public const WAITED_MIN_DAYS = 3;

    public function __construct(
        private readonly RewardRepository $rewards,
        private readonly DayPlanRepository $dayPlans,
        private readonly TaskRepository $tasks,
        private readonly TaskPostponement $postponement,
        private readonly TaskOverdue $overdue,
    ) {
    }

    /**
     * The surprise this task earns, done at $doneAt and not yet rewarded, with
     * the values its message reads; null for a standard reward.
     *
     * @return array{surprise: string, values: array<string, int>}|null
     */
    public function for(Task $task, \DateTimeImmutable $doneAt): ?array
    {
        $user = $task->getUser();
        $zone = $this->overdue->zone($user);
        $local = $doneAt->setTimezone($zone);
        $dayStart = $local->setTime(0, 0);
        $day = new \DateTimeImmutable($local->format('Y-m-d').' 00:00:00', new \DateTimeZone('UTC'));

        if ($this->rewards->hasSurpriseBetween($user, $dayStart, $dayStart->modify('+1 day'))) {
            return null;
        }

        if ($this->fillsTheDay($task, $day)) {
            return ['surprise' => Reward::SURPRISE_FULL_DAY, 'values' => []];
        }

        $record = $this->monthRecord($task, $local);
        if (null !== $record) {
            return ['surprise' => Reward::SURPRISE_MONTH_RECORD, 'values' => ['total' => $record]];
        }

        $since = $this->postponement->putOffSince($task, $day);
        if (null !== $since) {
            $days = (int) $since->diff($day)->days;
            if ($days >= self::WAITED_MIN_DAYS) {
                return ['surprise' => Reward::SURPRISE_WAITED, 'values' => ['jours' => $days]];
            }
        }

        return null;
    }

    private function fillsTheDay(Task $task, \DateTimeImmutable $day): bool
    {
        $plan = $this->dayPlans->findOneByUserAndDate($task->getUser(), $day);
        if (!$plan instanceof DayPlan || !\in_array((string) $task->getId(), $plan->getTaskIds(), true)) {
            return false;
        }

        // A slot whose task was deleted is left out, as the day shows it.
        $slotted = $this->tasks->findOwnedByIds($task->getUser(), $plan->getTaskIds());
        if (\count($slotted) < self::FULL_DAY_MIN_SLOTS) {
            return false;
        }
        foreach ($slotted as $slottedTask) {
            if (!$slottedTask->isDone()) {
                return false;
            }
        }

        return true;
    }

    /**
     * The month's stars, this one counted, when they pass the best month before
     * and no record was earned yet this month; null otherwise. A record a day's
     * other surprise held back comes with the next star.
     */
    private function monthRecord(Task $task, \DateTimeImmutable $local): ?int
    {
        $monthStart = $local->modify('first day of this month')->setTime(0, 0);
        $monthEnd = $monthStart->modify('+1 month');
        if ($this->rewards->hasSurpriseBetween($task->getUser(), $monthStart, $monthEnd, Reward::SURPRISE_MONTH_RECORD)) {
            return null;
        }

        $byMonth = [];
        foreach ($this->rewards->findStarDatesBefore($task->getUser(), $monthStart) as $earnedAt) {
            $month = $earnedAt->setTimezone($local->getTimezone())->format('Y-m');
            $byMonth[$month] = ($byMonth[$month] ?? 0) + 1;
        }
        if ([] === $byMonth) {
            return null;
        }

        $total = $this->rewards->countStarsBetween($task->getUser(), $monthStart, $monthEnd) + 1;

        return $total > max($byMonth) ? $total : null;
    }
}
