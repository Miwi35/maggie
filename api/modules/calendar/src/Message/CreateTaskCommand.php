<?php

namespace Maggie\Calendar\Message;

final readonly class CreateTaskCommand
{
    public function __construct(
        public string $name,
        public ?string $description = null,
        public ?string $priority = null,
        public ?string $criticality = null,
        public ?\DateTimeImmutable $dueDate = null,
        public ?\DateTimeImmutable $doneDate = null,
    ) {
    }
}
