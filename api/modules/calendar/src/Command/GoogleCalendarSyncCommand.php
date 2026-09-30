<?php

namespace Maggie\Calendar\Command;

use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Maggie\Calendar\Service\GoogleTasksSyncService;
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
        private readonly GoogleTasksSyncService $tasksSyncService,
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
            ->addOption('full', 'f', InputOption::VALUE_NONE, 'Force full sync (ignore sync token)')
            ->addOption('tasks', 't', InputOption::VALUE_NONE, 'Sync Google Tasks instead of calendar events');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $userId = $input->getOption('user');
        $agendaId = $input->getOption('agenda');
        $full = $input->getOption('full');
        $tasks = $input->getOption('tasks');

        if ($tasks) {
            return $this->syncTasks($io, $userId);
        }

        if ($agendaId) {
            $agenda = $this->agendaRepository->find($agendaId);
            if (null === $agenda || !$agenda->isGoogleSynced()) {
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
            if (null === $user) {
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
        $failed = 0;
        foreach ($agendas as $agenda) {
            $io->info("Syncing agenda: {$agenda->getName()}");

            if ($full) {
                $agenda->setGoogleSyncToken(null);
            }

            try {
                $this->syncService->pullFromGoogle($agenda);
                ++$count;
            } catch (\Throwable $e) {
                ++$failed;
                $io->warning("Failed to sync agenda {$agenda->getName()}: {$e->getMessage()}");
            }
        }

        // A non-zero exit is what the scheduler logs as a failed job.
        if ($failed > 0) {
            $io->error("Synced {$count} agenda(s), {$failed} failed.");

            return Command::FAILURE;
        }

        $io->success("Synced {$count} agenda(s).");

        return Command::SUCCESS;
    }

    private function syncTasks(SymfonyStyle $io, ?string $userId): int
    {
        if ($userId) {
            $user = $this->userRepository->find($userId);
            if (null === $user) {
                $io->error('User not found.');

                return Command::FAILURE;
            }
            $users = [$user];
        } else {
            $users = $this->userRepository->createQueryBuilder('u')
                ->where('u.googleRefreshToken IS NOT NULL')
                ->getQuery()
                ->getResult();
        }

        $count = 0;
        $failed = 0;
        foreach ($users as $user) {
            $io->info("Syncing tasks for user: {$user->getEmail()}");

            try {
                $this->tasksSyncService->pullFromGoogle($user);
                ++$count;
            } catch (\Throwable $e) {
                ++$failed;
                $io->warning("Failed to sync tasks for {$user->getEmail()}: {$e->getMessage()}");
            }
        }

        if ($failed > 0) {
            $io->error("Synced tasks for {$count} user(s), {$failed} failed.");

            return Command::FAILURE;
        }

        $io->success("Synced tasks for {$count} user(s).");

        return Command::SUCCESS;
    }
}
