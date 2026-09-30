<?php

namespace Maggie\Calendar\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateTaskCommand
{
    use ClearsFieldsTrait;

    /** @param list<'description'|'dueDate'|'completedAt'> $clearFields */
    public function __construct(
        public string $taskId,
        public ?string $title = null,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $completedAt = null,
        public array $clearFields = [],
    ) {
    }
}
