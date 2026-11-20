<?php

declare(strict_types=1);

namespace Maggie\Calendar\Command;

use Maggie\Calendar\Event\TaskOccurrencePassed;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Calendar\Specification\IsTaskOccurrenceOver;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:calendar:advance-recurring-tasks',
    description: 'Hand the rule of the recurring tasks whose day has passed undone to their next occurrence',
)]
class AdvanceRecurringTasksCommand extends Command
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly TaskOverdue $overdue,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $failed = 0;
        $announced = 0;

        foreach ($this->taskRepository->findOpenRecurring() as $task) {
            // Each owner's today, in their own timezone.
            if (!(new IsTaskOccurrenceOver($this->overdue->today($task->getUser())))->isSatisfiedBy($task)) {
                continue;
            }

            // One task failing must not hold the other series back.
            try {
                $this->eventBus->dispatch(new TaskOccurrencePassed((string) $task->getId(), (string) $task->getUser()->getId()));
                ++$announced;
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(\sprintf('Recurring task %s: %s', $task->getId(), $e->getMessage()));
            }
        }

        if (0 === $announced + $failed) {
            $io->info('No recurring task has passed its day.');
        } else {
            $io->success(\sprintf('%d recurring task(s) handed on.', $announced));
        }

        return 0 === $failed ? Command::SUCCESS : Command::FAILURE;
    }
}
