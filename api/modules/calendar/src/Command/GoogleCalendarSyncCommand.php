<?php

namespace Maggie\Calendar\Command;

use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'maggie:google-calendar:sync',
    description: 'Sync Google Calendar events for connected agendas',
)]
class GoogleCalendarSyncCommand extends Command
{
    public function __construct(
        private readonly GoogleCalendarSyncService $syncService,
        private readonly AgendaRepository $agendaRepository,
        private readonly UserRepository $userRepository,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('user', 'u', InputOption::VALUE_OPTIONAL, 'Sync only for a specific user ID')
            ->addOption('agenda', 'a', InputOption::VALUE_OPTIONAL, 'Sync only a specific agenda ID')
            ->addOption('full', 'f', InputOption::VALUE_NONE, 'Force full sync (ignore sync token)');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $userId = $input->getOption('user');
        $agendaId = $input->getOption('agenda');
        $full = $input->getOption('full');

        if ($agendaId) {
            $agenda = $this->agendaRepository->find($agendaId);
            if ($agenda === null || !$agenda->isGoogleSynced()) {
                $io->error('Agenda not found or not Google-synced.');
                return Command::FAILURE;
            }

            $io->info("Syncing agenda: {$agenda->getName()}");

            if ($full) {
                $agenda->setGoogleSyncToken(null);
            }

            $this->syncService->pullFromGoogle($agenda);
            $io->success('Sync complete.');
            return Command::SUCCESS;
        }

        if ($userId) {
            $user = $this->userRepository->find($userId);
            if ($user === null) {
                $io->error('User not found.');
                return Command::FAILURE;
            }

            $agendas = $this->agendaRepository->findGoogleSyncedByUser($user);
        } else {
            $agendas = $this->agendaRepository->createQueryBuilder('a')
                ->where('a.googleCalendarId IS NOT NULL')
                ->getQuery()
                ->getResult();
        }

        $count = 0;
        foreach ($agendas as $agenda) {
            $io->info("Syncing agenda: {$agenda->getName()}");

            if ($full) {
                $agenda->setGoogleSyncToken(null);
            }

            try {
                $this->syncService->pullFromGoogle($agenda);
                $count++;
            } catch (\Throwable $e) {
                $io->warning("Failed to sync agenda {$agenda->getName()}: {$e->getMessage()}");
            }
        }

        $io->success("Synced {$count} agenda(s).");
        return Command::SUCCESS;
    }
}
