<?php

declare(strict_types=1);

namespace Maggie\Calendar\Command;

use Maggie\Calendar\Holiday\FrenchHolidaysClient;
use Maggie\Calendar\Holiday\GenevaHolidays;
use Maggie\Calendar\Holiday\HolidayImporter;
use Maggie\Calendar\Holiday\HolidaySourceException;
use Maggie\Core\Repository\UserRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'maggie:calendar:import-holidays',
    description: 'File a year of public holidays (France, Geneva, both) in each user\'s « Fériés » agenda',
)]
class ImportHolidaysCommand extends Command
{
    public function __construct(
        private readonly FrenchHolidaysClient $frenchHolidays,
        private readonly HolidayImporter $importer,
        private readonly UserRepository $userRepository,
        private readonly ClockInterface $clock,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addArgument('year', InputArgument::OPTIONAL, 'The year to import (default: next year)');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $argument = $input->getArgument('year');
        if (null === $argument) {
            $year = (int) $this->clock->now()->format('Y') + 1;
        } elseif (\is_string($argument) && 1 === preg_match('/^\d{4}$/', $argument)) {
            $year = (int) $argument;
        } else {
            $io->error('The year is four digits, e.g. 2027.');

            return Command::INVALID;
        }

        // France failing leaves France out whole, never half of it: Geneva is still filed,
        // and the next run adds France's days and turns the shared ones to `both`.
        $france = null;
        try {
            $france = $this->frenchHolidays->forYear($year);
        } catch (HolidaySourceException $e) {
            $io->error(\sprintf('French holidays of %d not imported: %s', $year, $e->getMessage()));
        }
        $days = HolidayImporter::merge($france, GenevaHolidays::forYear($year));

        $failed = 0;
        $created = 0;
        $updated = 0;
        foreach ($this->userRepository->findAll() as $user) {
            // One user failing must not keep the others without their holidays.
            try {
                $result = $this->importer->importFor($user, $days);
                $created += $result['created'];
                $updated += $result['updated'];
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(\sprintf('User %s: %s', $user->getId(), $e->getMessage()));
            }
        }

        $io->success(\sprintf('Holidays of %d: %d period(s) created, %d corrected.', $year, $created, $updated));

        return null === $france || 0 !== $failed ? Command::FAILURE : Command::SUCCESS;
    }
}
