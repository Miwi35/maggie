<?php

declare(strict_types=1);

namespace Maggie\Health\Command;

use Maggie\Health\Message\FilePlannedWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Files in the « Santé » module agenda the planned sessions stored before it
 * existed (MAG-777), through the command the domain events use: the result is
 * the one a save would have given, and the open screens and the search index
 * are told. Safe to run again — once every planned session has its event,
 * there is nothing left to do.
 */
#[AsCommand(
    name: 'app:health:file-planned-workouts',
    description: 'File every planned workout without an event in its user\'s « Santé » module agenda',
)]
final class FilePlannedWorkoutsCommand extends Command
{
    public function __construct(
        private readonly WorkoutRepository $workoutRepository,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'Report what would be filed and change nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $pending = $this->workoutRepository->findPlannedWithoutEvent();
        if ([] === $pending) {
            $io->success('Every planned workout is already in its module agenda.');

            return Command::SUCCESS;
        }

        if (!$dryRun) {
            foreach ($pending as [$workoutId, $userId]) {
                $this->bus->dispatch(new FilePlannedWorkoutCommand($userId, $workoutId));
            }
        }

        $io->success(\sprintf('%s%d planned workout(s) filed in the module agenda.', $dryRun ? '[dry-run] ' : '', \count($pending)));

        return Command::SUCCESS;
    }
}
