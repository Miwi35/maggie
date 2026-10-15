<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;

/**
 * The preview of « Ce qui passe en premier » (MAG-407): five of the user's
 * open tasks, in the order their weights give. The five are the first by the
 * spec's weights, whatever the user's: moving a slider reorders the same
 * tasks rather than swapping them for others.
 */
final class TaskOrderPreview
{
    public const SIZE = 5;

    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly TaskUrgency $urgency,
    ) {
    }

    /** @return list<Task> each with its `overdueDays`, `urgency` and `orderScore` filled */
    public function for(User $user): array
    {
        $today = $this->urgency->today($user);
        $byDefault = array_map(
            fn (Task $task): array => [$this->urgency->orderScore($task, $today, TaskOrderWeights::DEFAULTS), $task],
            $this->taskRepository->findPending($user),
        );
        usort($byDefault, static fn (array $a, array $b): int => [$b[0], (string) $a[1]->getId()] <=> [$a[0], (string) $b[1]->getId()]);

        $sample = array_map(static fn (array $scored): Task => $scored[1], \array_slice($byDefault, 0, self::SIZE));

        return $this->urgency->sort(array_map($this->urgency->mark(...), $sample));
    }
}
