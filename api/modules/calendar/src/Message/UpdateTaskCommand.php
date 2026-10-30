<?php

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateTaskCommand
{
    use ClearsFieldsTrait;

    /**
     * @param list<'description'|'dueDate'|'completedAt'|'parent'|'estimatedMinutes'|'firstStep'|'scheduledDate'|'scheduledMoment'> $clearFields
     * @param list<int>|null                                                                                                        $reminders   minutes before the time, [] for none; null: unchanged
     */
    public function __construct(
        public string $taskId,
        public ?string $title = null,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $completedAt = null,
        public array $clearFields = [],
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
