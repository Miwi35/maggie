<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Repository\TaskSettingsRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\Clock\ClockInterface;

/**
 * The morning proposal (MAG-498): the five tasks {@see DayProposalRule} picks
 * for a day (« due today » is due that day), with the free time of the agenda they were measured against.
 * It writes nothing — not even the empty day — « C'est parti » is the PUT of
 * the day that does.
 */
final class DayProposal
{
    /** The evening after which the free time is no one's to plan. */
    public const DAY_END = '21:00';

    public function __construct(
        private readonly DayPlanRepository $dayPlanRepository,
        private readonly TaskRepository $taskRepository,
        private readonly TaskSettingsRepository $settingsRepository,
        private readonly EventRepository $eventRepository,
        private readonly RecurrenceService $recurrenceService,
        private readonly TaskUrgency $urgency,
        private readonly TaskOverdue $overdue,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * Every entry has `reason` (see {@see DayProposalRule}), `minutes` (the
     * estimate, 15 when unknown) and its `task`.
     *
     * @return array{date: string, slots: list<array<string, mixed>>, reserveRanked: list<array<string, mixed>>, totalMinutes: int, freeMinutes: int}
     */
    public function propose(User $user, \DateTimeImmutable $date): array
    {
        $plan = $this->dayPlanRepository->findOneByUserAndDate($user, $date);
        $writtenIds = $plan?->getTaskIds() ?? [];
        $written = $this->taskRepository->findOwnedByIds($user, $writtenIds);

        $kept = [];
        foreach ($writtenIds as $taskId) {
            if (isset($written[$taskId])) {
                $kept[] = $this->urgency->mark($written[$taskId]);
            }
        }
        $candidates = array_values(array_map(
            $this->urgency->mark(...),
            array_filter(
                $this->taskRepository->findOpenTopLevel($user),
                static fn (Task $task): bool => !isset($written[(string) $task->getId()]),
            ),
        ));

        $freeMinutes = $this->freeMinutes($user, $date);
        $proposal = DayProposalRule::propose($kept, $candidates, $date, $freeMinutes);

        $entry = static fn (Task $task): array => [
            'reason' => DayProposalRule::reason($task, $date),
            'minutes' => DayProposalRule::minutes($task),
            'task' => DayPlans::task($task),
        ];

        $slots = [];
        foreach ($proposal['slots'] as $task) {
            $slots[] = ['position' => \count($slots) + 1, 'type' => 'task'] + $entry($task);
        }

        return [
            'date' => $date->format('Y-m-d'),
            'slots' => $slots,
            'reserveRanked' => array_map($entry, $proposal['reserve']),
            'totalMinutes' => array_sum(array_map(
                static fn (Task $task): int => $task->isDone() ? 0 : DayProposalRule::minutes($task),
                $proposal['slots'],
            )),
            'freeMinutes' => $freeMinutes,
        ];
    }

    /**
     * The minutes of the day no event takes, from the morning time (or now,
     * when later) to 21:00 in the user's timezone. All-day events and Maggie's
     * own proactions take no time.
     */
    public function freeMinutes(User $user, \DateTimeImmutable $date): int
    {
        $zone = $this->overdue->zone($user);
        $day = $date->format('Y-m-d');
        $morning = $this->settingsRepository->findOneByUser($user)?->getMorningTime() ?? TaskSettings::DEFAULT_MORNING_TIME;

        $start = max(new \DateTimeImmutable("{$day} {$morning}", $zone), $this->clock->now());
        $end = new \DateTimeImmutable($day.' '.self::DAY_END, $zone);
        if ($start >= $end) {
            return 0;
        }

        $busy = [];
        $events = $this->recurrenceService->expandAll($this->eventRepository->findByDateRange($user, $start, $end), $start, $end);
        foreach ($events as $event) {
            if ($event->isAllDay() || $event instanceof Proaction) {
                continue;
            }
            $from = max($event->getStartInstant(), $start)->getTimestamp();
            $to = min($event->getEndInstant(), $end)->getTimestamp();
            if ($to > $from) {
                $busy[] = [$from, $to];
            }
        }

        sort($busy);
        $taken = 0;
        $reached = $start->getTimestamp();
        foreach ($busy as [$from, $to]) {
            $from = max($from, $reached);
            if ($to > $from) {
                $taken += $to - $from;
                $reached = $to;
            }
        }

        return intdiv($end->getTimestamp() - $start->getTimestamp() - $taken, 60);
    }
}
