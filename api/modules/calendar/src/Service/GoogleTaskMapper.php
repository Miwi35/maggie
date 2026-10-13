<?php

namespace Maggie\Calendar\Service;

use Google\Model as GoogleModel;
use Google\Service\Tasks\Task as GoogleTask;
use Google\Service\Tasks\TaskLinks as GoogleTaskLink;
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

        // Google sends the due day as an RFC 3339 midnight UTC; its API
        // keeps no time of day, so the day is all there is to read.
        /** @var ?string $due */
        $due = $googleTask->getDue();
        $task->setDueDate($due ? self::day($due) : null);

        // The order among the siblings. The parent is a Google id: the sync
        // resolves it once every task of the list is known.
        /** @var ?string $position */
        $position = $googleTask->getPosition();
        $task->setPosition($position);

        // Read only: Google's API writes no link.
        $links = [];
        /** @var ?GoogleTaskLink[] $googleLinks */
        $googleLinks = $googleTask->getLinks();
        foreach ($googleLinks ?? [] as $link) {
            $links[] = [
                'type' => (string) $link->getType(),
                'description' => (string) $link->getDescription(),
                'link' => (string) $link->getLink(),
            ];
        }
        $task->setLinks($links);

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
            // A plain null is left out of the body: the library's marker sends it.
            $googleTask->setDue(self::googleDue($task->getDueDate()) ?? GoogleModel::NULL_VALUE);
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

        $googleTask->setDue(self::googleDue($task->getDueDate()));

        // Completion status
        if (null !== $task->getCompletedAt()) {
            $googleTask->setStatus('completed');
            $googleTask->setCompleted($task->getCompletedAt()->format(\DateTimeInterface::RFC3339));
        } else {
            $googleTask->setStatus('needsAction');
        }

        return $googleTask;
    }

    /** The day of an RFC 3339 date-time, as Google writes `due`: its date part. */
    private static function day(string $due): \DateTimeImmutable
    {
        $day = \DateTimeImmutable::createFromFormat('!Y-m-d', substr($due, 0, 10));

        return false !== $day ? $day : new \DateTimeImmutable(substr($due, 0, 10));
    }

    /** A day as Google wants `due`: midnight UTC, the time being dropped anyway. */
    private static function googleDue(?\DateTimeImmutable $day): ?string
    {
        return null === $day ? null : $day->format('Y-m-d').'T00:00:00.000Z';
    }
}
