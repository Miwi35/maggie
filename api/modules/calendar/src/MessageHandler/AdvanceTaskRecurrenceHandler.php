<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Doctrine\DBAL\LockMode;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\AdvanceTaskRecurrenceCommand;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Service\TaskRecurrence;
use Maggie\Calendar\Specification\IsTaskOccurrenceOver;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The rule of a recurring task goes to its next occurrence (MAG-870) once the
 * current one is over: done, or its day passed. The current one loses the
 * rule and nothing else — a missed one stays open, « reportée » — and points
 * to the next one, keeping the day of the rule it stood for. The next one
 * falls on the first day of the rule after the one this stands for, from
 * today on; none when the rule has run out (UNTIL).
 */
#[AsMessageHandler]
class AdvanceTaskRecurrenceHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly TaskOverdue $overdue,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @return Task|null the next occurrence, null when none was created */
    public function __invoke(AdvanceTaskRecurrenceCommand $command): ?Task
    {
        $task = $this->em->getRepository(Task::class)->findOneBy(['id' => $command->taskId, 'user' => $command->userId]);
        if (!$task instanceof Task) {
            return null;
        }
        $today = $this->overdue->today($task->getUser());
        $over = new IsTaskOccurrenceOver($today);
        if (!$over->isSatisfiedBy($task)) {
            return null;
        }

        // The row is locked and read again: the tick and the daily run, or two
        // runs, hand the rule over once.
        return $this->em->wrapInTransaction(function () use ($task, $today, $over): ?Task {
            $this->em->refresh($task, LockMode::PESSIMISTIC_WRITE);
            if (!$over->isSatisfiedBy($task)) {
                return null;
            }

            $rrule = (string) $task->getRrule();
            $from = $task->getRecurrenceDate() ?? TaskRecurrence::anchor($task);
            $nextDay = TaskRecurrence::nextDay($task, $today);
            $task->handOverTo(null);
            $this->em->flush();

            if (null === $nextDay || null === $from) {
                return null;
            }

            // The day the rule is anchored on lands on the next day of the
            // series, even when this occurrence was moved; the other date
            // keeps its gap in days to the series' day.
            $scheduledDate = $task->getScheduledDate();
            $dueDate = $task->getDueDate();
            if (null !== $scheduledDate) {
                $scheduledDate = $nextDay;
                $dueDate = self::shift($dueDate, $from, $nextDay);
            } else {
                $dueDate = $nextDay;
            }

            $envelope = $this->bus->dispatch(new CreateTaskCommand(
                userId: (string) $task->getUser()->getId(),
                title: $task->getTitle(),
                description: $task->getDescription(),
                priority: $task->getPriority()->value,
                criticality: $task->getCriticality()->value,
                dueDate: $dueDate,
                estimatedMinutes: $task->getEstimatedMinutes(),
                firstStep: $task->getFirstStep(),
                scheduledDate: $scheduledDate,
                scheduledMoment: $task->getScheduledMoment()?->value,
                scheduledTime: $task->getScheduledTime(),
                reminders: $task->getReminders(),
                timed: $task->isTimed(),
                rrule: $rrule,
                recurrenceDate: $nextDay,
            ));

            $next = $envelope->last(HandledStamp::class)?->getResult();
            if ($next instanceof Task) {
                // Reopening this one gives the series back (MAG-1033).
                $task->handOverTo($next);
                $this->em->flush();
            }

            return $next;
        });
    }

    /** $date moved as the series moves from $from to $to: same gap in days to the series' day. */
    private static function shift(?\DateTimeImmutable $date, \DateTimeImmutable $from, \DateTimeImmutable $to): ?\DateTimeImmutable
    {
        if (null === $date) {
            return null;
        }
        $utc = new \DateTimeZone('UTC');
        $gap = (int) (new \DateTimeImmutable($from->format('Y-m-d'), $utc))->diff(new \DateTimeImmutable($date->format('Y-m-d'), $utc))->format('%r%a');

        return $to->modify(\sprintf('%+d days', $gap));
    }
}
