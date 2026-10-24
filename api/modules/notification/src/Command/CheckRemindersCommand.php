<?php

declare(strict_types=1);

namespace Maggie\Notification\Command;

use Maggie\Core\Reminder\ReminderSourceInterface;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;

#[AsCommand(
    name: 'maggie:notification:check-reminders',
    description: 'Check for due event reminders and task alerts and create notifications',
)]
class CheckRemindersCommand extends Command
{
    /** @param iterable<ReminderSourceInterface> $sources the reminders every module declares */
    public function __construct(
        #[AutowireIterator(ReminderSourceInterface::TAG)]
        private readonly iterable $sources,
        private readonly ClockInterface $clock,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = $this->clock->now();

        $created = 0;

        foreach ($this->sources as $source) {
            $report = $source->remind($now);

            foreach ($report->lines as $line) {
                $io->writeln($line);
            }
            foreach ($report->warnings as $warning) {
                $io->warning($warning);
            }
            $created += $report->created;
        }

        if (0 === $created) {
            $io->info('No due reminders found.');
        } else {
            $io->success(\sprintf('Created %d reminder notification(s).', $created));
        }

        return Command::SUCCESS;
    }
}
