<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp;

use Maggie\Calendar\Entity\Task;

/** A task as the task tools read and write it — one shape for the three of them. */
final class TaskJson
{
    /** @return array<string, mixed> */
    public static function of(Task $task): array
    {
        return [
            'id' => (string) $task->getId(),
            'title' => $task->getTitle(),
            'description' => $task->getDescription(),
            'priority' => $task->getPriority()->value,
            'criticality' => $task->getCriticality()->value,
            'dueDate' => $task->getDueDate()?->format('Y-m-d'),
            'isDone' => $task->isDone(),
            'completedAt' => $task->getCompletedAt()?->format('c'),
            'parentId' => null !== $task->getParent() ? (string) $task->getParent()->getId() : null,
            'position' => $task->getPosition(),
            'links' => $task->getLinks(),
        ];
    }

    /**
     * The due day a tool was given, YYYY-MM-DD and a day that exists — not
     * the 45th rolled over into the next month.
     *
     * @throws \InvalidArgumentException
     */
    public static function day(string $day): \DateTimeImmutable
    {
        $parsed = \DateTimeImmutable::createFromFormat('!Y-m-d', $day);
        if (false === $parsed || $parsed->format('Y-m-d') !== $day) {
            throw new \InvalidArgumentException("dueDate must be a day, YYYY-MM-DD: \"{$day}\".");
        }

        return $parsed;
    }
}
