<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateDayPlanCommand;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The day as every screen and Maggie read it (MAG-497): its slots with their
 * task, `now` — the first slot not done — and the reserve, every open task
 * that is not a subtask and holds no slot, in the order of the score.
 *
 * A new day starts empty: what yesterday left undone is not carried over, it
 * waits in the reserve, marked « reportée » ({@see TaskPostponement}).
 */
final class DayPlans
{
    public function __construct(
        private readonly DayPlanRepository $repository,
        private readonly TaskRepository $taskRepository,
        private readonly TaskUrgency $urgency,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** The user's day, laid down empty on first read through the bus, so the open screens hear of it. */
    public function forDate(User $user, \DateTimeImmutable $date): DayPlan
    {
        $plan = $this->repository->findOneByUserAndDate($user, $date);
        if (null !== $plan) {
            return $plan;
        }

        $created = $this->bus->dispatch(new CreateDayPlanCommand((string) $user->getId(), $date))
            ->last(HandledStamp::class)?->getResult();
        \assert($created instanceof DayPlan);

        return $created;
    }

    public function today(User $user): \DateTimeImmutable
    {
        return $this->urgency->today($user);
    }

    /**
     * A day given as YYYY-MM-DD, a day that exists.
     *
     * @throws \InvalidArgumentException
     */
    public static function day(string $day): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day, new \DateTimeZone('UTC'));
        if (false === $parsed || $parsed->format('Y-m-d') !== $day) {
            throw new \InvalidArgumentException("date must be a day, YYYY-MM-DD: \"{$day}\".");
        }

        return $parsed;
    }

    /**
     * A slot whose task was deleted is left out.
     *
     * @return array{id: string, date: string, confirmedAt: string|null, slots: list<array<string, mixed>>, now: array<string, mixed>|null, reserve: list<array<string, mixed>>, done: int, total: int}
     */
    public function describe(DayPlan $plan): array
    {
        $user = $plan->getUser();
        $tasks = $this->taskRepository->findOwnedByIds($user, $plan->getTaskIds());

        $slots = [];
        foreach ($plan->getSlots() as $slot) {
            $task = $tasks[$slot['taskId']] ?? null;
            if (null === $task) {
                continue;
            }
            $slots[] = [
                'position' => \count($slots) + 1,
                'type' => $slot['type'],
                'task' => self::task($this->urgency->mark($task)),
            ];
        }

        $now = null;
        foreach ($slots as $slot) {
            if (null === $slot['task']['completedAt']) {
                $now = $slot;
                break;
            }
        }

        $reserve = array_values(array_filter(
            $this->taskRepository->findOpenTopLevel($user),
            static fn (Task $task): bool => !isset($tasks[(string) $task->getId()]),
        ));

        return [
            'id' => (string) $plan->getId(),
            'date' => $plan->getDate()->format('Y-m-d'),
            'confirmedAt' => $plan->getConfirmedAt()?->format(\DATE_ATOM),
            'slots' => $slots,
            'now' => $now,
            'reserve' => array_map(self::task(...), $this->urgency->sort(array_map($this->urgency->mark(...), $reserve))),
            'done' => \count(array_filter($slots, static fn (array $slot): bool => null !== $slot['task']['completedAt'])),
            'total' => \count($slots),
        ];
    }

    /**
     * A task as the day shows it. `urgency` and `criticality` keep the mark of
     * a critical task, which « reportée » does not replace.
     *
     * @return array<string, mixed>
     */
    public static function task(Task $task): array
    {
        return [
            'id' => (string) $task->getId(),
            'title' => $task->getTitle(),
            'estimatedMinutes' => $task->getEstimatedMinutes(),
            'firstStep' => $task->getFirstStep(),
            'completedAt' => $task->getCompletedAt()?->format(\DATE_ATOM),
            'dueDate' => $task->getDueDate()?->format('Y-m-d'),
            'postponedSince' => $task->getPostponedSince()?->format('Y-m-d'),
            'urgency' => $task->getUrgency()?->value,
            'criticality' => $task->getCriticality()->value,
        ];
    }
}
