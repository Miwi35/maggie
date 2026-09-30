<?php

namespace Maggie\Calendar\Command;

use Maggie\Calendar\Repository\AgendaRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

/**
 * Fails when a Google-linked agenda has not been pulled recently (MAG-147).
 *
 * The sync ran every five minutes on paper and stood still for months without
 * anyone seeing it. The cron pod runs this check itself, so a stalled sync
 * leaves an error in `kubectl logs deploy/cron`; a post-deploy smoke test
 * would only look once per release, and would roll back a healthy release
 * because Google refused a token.
 */
#[AsCommand(
    name: 'maggie:google-calendar:check-sync',
    description: 'Fail if a Google-synced agenda has not been synchronized recently',
)]
class GoogleCalendarCheckSyncCommand extends Command
{
    public function __construct(private readonly AgendaRepository $agendaRepository)
    {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('max-age', null, InputOption::VALUE_REQUIRED, 'Maximum age of the last sync, in minutes', '60');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        $maxAge = filter_var($input->getOption('max-age'), FILTER_VALIDATE_INT, ['options' => ['min_range' => 1]]);
        if (false === $maxAge) {
            $io->error('--max-age must be a positive number of minutes.');

            return Command::INVALID;
        }

        $stale = $this->agendaRepository->findGoogleSyncedNotSyncedSince(
            new \DateTimeImmutable("-{$maxAge} minutes"),
        );

        if ([] === $stale) {
            $io->success('Every Google-synced agenda was synchronized within the last '.$maxAge.' minute(s).');

            return Command::SUCCESS;
        }

        foreach ($stale as $agenda) {
            $last = $agenda->getLastGoogleSyncAt();
            $io->writeln(sprintf(
                'Agenda "%s" (%s): %s',
                $agenda->getName(),
                $agenda->getId(),
                null === $last ? 'never synchronized' : 'last synchronized '.$last->format(\DATE_ATOM),
            ));
        }
        $io->error(sprintf('%d Google-synced agenda(s) not synchronized for more than %d minute(s).', count($stale), $maxAge));

        return Command::FAILURE;
    }
}
