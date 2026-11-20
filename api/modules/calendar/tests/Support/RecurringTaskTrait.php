<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Support;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Message\CreateTaskCommand;
use Maggie\Calendar\Message\UpdateTaskCommand;
use Maggie\Core\Entity\User;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Recurring tasks written and read through the bus, every door's way in
 * (MAG-870). Tuesday 2026-11-24 is a day of « un mardi sur deux ».
 */
trait RecurringTaskTrait
{
    private const EVERY_OTHER_TUESDAY = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU';

    /** Pins today, noon in Paris. */
    private function today(string $day): void
    {
        Clock::set(new MockClock($day.' 11:00:00 UTC'));
    }

    /** @param list<int>|null $reminders */
    private function createRecurring(
        User $user,
        string $scheduledDate = '2026-11-24',
        string $rrule = self::EVERY_OTHER_TUESDAY,
        ?string $dueDate = null,
        ?string $moment = null,
        ?string $time = null,
        ?array $reminders = null,
        string $title = 'Sortir la poubelle jaune',
    ): Task {
        return $this->handled(new CreateTaskCommand(
            userId: (string) $user->getId(),
            title: $title,
            priority: 'high',
            dueDate: null !== $dueDate ? new \DateTimeImmutable($dueDate) : null,
            estimatedMinutes: 5,
            firstStep: 'Descendre le bac',
            scheduledDate: new \DateTimeImmutable($scheduledDate),
            scheduledMoment: $moment,
            scheduledTime: $time,
            reminders: $reminders,
            rrule: $rrule,
        ));
    }

    private function complete(Task $task): Task
    {
        return $this->handled(new UpdateTaskCommand(taskId: (string) $task->getId(), completedAt: Clock::get()->now()));
    }

    private function handled(object $command): mixed
    {
        $bus = self::getContainer()->get('messenger.bus.default');
        \assert($bus instanceof MessageBusInterface);

        return $bus->dispatch($command)->last(HandledStamp::class)?->getResult();
    }

    /** @return list<Task> the tasks of that title, the earliest planned first */
    private function series(string $title = 'Sortir la poubelle jaune'): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(Task::class)->findBy(['title' => $title], ['scheduledDate' => 'ASC', 'dueDate' => 'ASC']);
    }
}
