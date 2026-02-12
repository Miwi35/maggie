<?php

namespace Maggie\Calendar\Message;

final readonly class UpdateTaskCommand
{
    public function __construct(
        public string $taskId,
        public ?string $name = null,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $doneDate = null,
    ) {
    }
}
