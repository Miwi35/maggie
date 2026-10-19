<?php

declare(strict_types=1);

namespace Maggie\Calendar\Message;

/** A button pressed on a task reminder (MAG-510): one of {@see \Maggie\Calendar\Entity\TaskReminder::ACTIONS}. */
final readonly class AnswerTaskReminderCommand
{
    public function __construct(
        public string $userId,
        public string $reminderId,
        public string $action,
    ) {
    }
}
