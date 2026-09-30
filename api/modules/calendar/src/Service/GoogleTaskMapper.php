<?php

namespace Maggie\Calendar\Service;

use Google\Service\Tasks\Task as GoogleTask;
use Maggie\Calendar\Entity\Task;
use Maggie\Core\Entity\User;

class GoogleTaskMapper
{
    public function fromGoogle(GoogleTask $googleTask, User $user, ?Task $existing = null): Task
    {
        $task = $existing ?? new Task();
        $task->setUser($user);

        /** @var string $title */
        $title = $googleTask->getTitle();
        $task->setTitle($title ?: '(No title)');
        $task->setDescription($googleTask->getNotes());

        // Due date handling — Google Tasks stores due as RFC3339 date-time
        /** @var ?string $due */
        $due = $googleTask->getDue();
        if ($due) {
            $task->setDueDate(new \DateTimeImmutable($due));
        } else {
            $task->setDueDate(null);
        }

        // Completion status
        /** @var ?string $status */
        $status = $googleTask->getStatus();
        if ('completed' === $status) {
            /** @var ?string $completed */
            $completed = $googleTask->getCompleted();
            if ($completed) {
                $task->setCompletedAt(new \DateTimeImmutable($completed));
            } else {
                $task->setCompletedAt(new \DateTimeImmutable());
            }
        } else {
            $task->setCompletedAt(null);
        }

        // Google tracking fields
        $task->setGoogleTaskId($googleTask->getId());
        $task->setGoogleTaskEtag($googleTask->getEtag());
        /** @var ?string $updated */
        $updated = $googleTask->getUpdated();
        if ($updated) {
            $task->setGoogleTaskUpdatedAt(new \DateTimeImmutable($updated));
        }

        return $task;
    }

    /**
     * Build a partial GoogleTask containing only the specified fields, for PATCH.
     *
     * @param string[] $changedFields
     */
    public function toGooglePatch(Task $task, array $changedFields): GoogleTask
    {
        $googleTask = new GoogleTask();
        $fields = array_flip($changedFields);

        if (isset($fields['title'])) {
            $googleTask->setTitle($task->getTitle());
        }
        if (isset($fields['description'])) {
            $googleTask->setNotes($task->getDescription());
        }
        if (isset($fields['dueDate'])) {
            if (null !== $task->getDueDate()) {
                $googleTask->setDue($task->getDueDate()->format(\DateTimeInterface::RFC3339));
            }
        }
        if (isset($fields['completedAt'])) {
            if (null !== $task->getCompletedAt()) {
                $googleTask->setStatus('completed');
                $googleTask->setCompleted($task->getCompletedAt()->format(\DateTimeInterface::RFC3339));
            } else {
                $googleTask->setStatus('needsAction');
            }
        }

        return $googleTask;
    }

    public function toGoogle(Task $task): GoogleTask
    {
        $googleTask = new GoogleTask();

        $googleTask->setTitle($task->getTitle());
        $googleTask->setNotes($task->getDescription());

        // Due date
        if (null !== $task->getDueDate()) {
            $googleTask->setDue($task->getDueDate()->format(\DateTimeInterface::RFC3339));
        }

        // Completion status
        if (null !== $task->getCompletedAt()) {
            $googleTask->setStatus('completed');
            $googleTask->setCompleted($task->getCompletedAt()->format(\DateTimeInterface::RFC3339));
        } else {
            $googleTask->setStatus('needsAction');
        }

        return $googleTask;
    }
}
