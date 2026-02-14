<?php

namespace Maggie\Calendar\Message;

final readonly class CreateTaskCommand
{
    public function __construct(
        public string $userId,
        public string $title,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $completedAt = null,
    ) {
    }
}
