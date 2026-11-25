<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\DBAL\LockMode;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\DeleteTaskCommand;
use Maggie\Calendar\Message\RewindTaskRecurrenceCommand;
use Maggie\Calendar\Specification\CanTaskTakeBackItsRule;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * A reopened occurrence gives the series back (MAG-1033): it takes the rule
 * back on the day of the rule it kept, and the next occurrence it handed the
 * rule to is deleted. A next one done, without rule or with subtasks is kept —
 * its subtasks would go with it — and the occurrence reopens alone.
 */
#[AsMessageHandler]
class RewindTaskRecurrenceHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(RewindTaskRecurrenceCommand $command): void
    {
        $task = $this->em->getRepository(Task::class)->findOneBy(['id' => $command->taskId, 'user' => $command->userId]);
        if (!$task instanceof Task) {
            return;
        }
        $canTakeBack = new CanTaskTakeBackItsRule();
        if (!$canTakeBack->isSatisfiedBy($task)) {
            return;
        }

        // Both rows are locked and read again: two reopenings give the series back once.
        $this->em->wrapInTransaction(function () use ($task, $canTakeBack): void {
            $this->em->refresh($task, LockMode::PESSIMISTIC_WRITE);
            $next = $task->getNextOccurrence();
            if (null === $next) {
                return;
            }
            $this->em->refresh($next, LockMode::PESSIMISTIC_WRITE);
            if (!$canTakeBack->isSatisfiedBy($task)) {
                return;
            }
            if ([] !== $this->em->getRepository(Task::class)->findBy(['parent' => $next], limit: 1)) {
                return;
            }

            $nextId = (string) $next->getId();
            $task->takeBackRule((string) $next->getRrule());
            $this->em->flush();

            // Google, Mercure and the index follow the deletion there.
            $this->bus->dispatch(new DeleteTaskCommand($nextId));
        });
    }
}
