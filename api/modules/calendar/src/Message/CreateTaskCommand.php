<?php

namespace Maggie\Calendar\Message;

final readonly class CreateTaskCommand
{
    /** @param list<int>|null $reminders minutes before the time; null: the default at a precise time */
    public function __construct(
        public string $userId,
        public string $title,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $completedAt = null,
        public ?string $parentId = null,
        public ?string $position = null,
        public ?int $estimatedMinutes = null,
        public ?string $firstStep = null,
        public ?\DateTimeImmutable $scheduledDate = null,
        public ?string $scheduledMoment = null,
        public ?string $scheduledTime = null,
        public ?array $reminders = null,
    ) {
    }
}
