<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Message\AnswerTaskReminderCommand;
use Maggie\Calendar\Repository\TaskReminderRepository;
use Maggie\Calendar\Service\TaskReminders;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Through the bus, so the notification it rewrites or takes away reaches the
 * open screens and the index. Refusals are {@see \DomainException}s, the
 * caller's to read.
 */
#[AsMessageHandler]
class AnswerTaskReminderHandler
{
    public function __construct(
        private readonly TaskReminderRepository $repository,
        private readonly TaskReminders $reminders,
    ) {
    }

    /** @return array<string, mixed> */
    public function __invoke(AnswerTaskReminderCommand $command): array
    {
        $reminder = $this->repository->findOneBy(['id' => $command->reminderId, 'user' => $command->userId]);
        if (!$reminder instanceof TaskReminder) {
            throw new \DomainException("Task reminder not found: {$command->reminderId}");
        }

        try {
            return $this->reminders->act($reminder, $command->action);
        } catch (\InvalidArgumentException $e) {
            throw new \DomainException($e->getMessage(), 0, $e);
        }
    }
}
