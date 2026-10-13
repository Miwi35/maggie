<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Exception\InvalidTaskParentException;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Core\Entity\User;
use Symfony\Component\Uid\Ulid;

/**
 * Subtasks as Google Tasks has them (MAG-404): one level, ordered by a
 * position string among the tasks of the same parent.
 */
class TaskHierarchy
{
    /** Google's positions are twenty digits; a new one keeps that shape. */
    private const POSITION_LENGTH = 20;

    public function __construct(
        private readonly TaskRepository $taskRepository,
    ) {
    }

    /**
     * The parent $parentId names, checked for $task: the user's own task,
     * not $task itself, not a subtask, and $task with no subtask of its own —
     * either would make a second level, which Google does not have.
     *
     * @param Task|null $task null while the task is being created
     */
    public function parentFor(?Task $task, User $user, string $parentId): Task
    {
        $parent = Ulid::isValid($parentId) ? $this->taskRepository->find($parentId) : null;
        if (null === $parent || !$parent->getUser()->getId()->equals($user->getId())) {
            throw new InvalidTaskParentException("Parent task not found: {$parentId}.");
        }
        if (null !== $task && $parent->getId()->equals($task->getId())) {
            throw new InvalidTaskParentException('A task cannot be its own subtask.');
        }
        if (null !== $parent->getParent()) {
            throw new InvalidTaskParentException("A subtask cannot have subtasks: « {$parent->getTitle()} » is already a subtask of « {$parent->getParent()->getTitle()} ».");
        }
        if (null !== $task && $this->taskRepository->count(['parent' => $task]) > 0) {
            throw new InvalidTaskParentException("A task with subtasks cannot become a subtask: « {$task->getTitle()} » has some.");
        }

        return $parent;
    }

    /**
     * Gives $task $position. A sibling holding it already gives its place and
     * takes $task's: moving up or down is one write on the moved task, never
     * two — each of which would go to Google on its own and, between them,
     * leave two siblings tied (MAG-404).
     */
    public function moveTo(Task $task, string $position): void
    {
        $previous = $task->getPosition();
        foreach ($this->taskRepository->findSiblings($task->getUser(), $task->getParent()) as $sibling) {
            if ($sibling !== $task && $sibling->getPosition() === $position && null !== $previous) {
                $sibling->setPosition($previous);
            }
        }
        $task->setPosition($position);
    }

    /** A position after every sibling under $parent, $except left out. */
    public function lastPosition(User $user, ?Task $parent, ?Task $except = null): string
    {
        $last = null;
        foreach ($this->taskRepository->findSiblings($user, $parent) as $sibling) {
            if (null !== $except && $sibling->getId()->equals($except->getId())) {
                continue;
            }
            $position = $sibling->getPosition();
            if (null !== $position && (null === $last || strcmp($position, $last) > 0)) {
                $last = $position;
            }
        }

        return self::after($last);
    }

    /**
     * The next position after $position, compared as a string. A string of
     * digits is incremented in place, as Google numbers; anything else — and
     * a string of nines — gets a digit appended, which sorts after it.
     */
    public static function after(?string $position): string
    {
        if (null === $position || '' === $position) {
            return str_repeat('0', self::POSITION_LENGTH);
        }
        if (!ctype_digit($position)) {
            return $position.'0';
        }

        // Incremented at its own length: padding it would sort it before itself.
        $digits = $position;
        for ($i = \strlen($digits) - 1; $i >= 0; --$i) {
            if ('9' !== $digits[$i]) {
                $digits[$i] = (string) ((int) $digits[$i] + 1);

                return $digits;
            }
            $digits[$i] = '0';
        }

        return $position.'0';
    }
}
