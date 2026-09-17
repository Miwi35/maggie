<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\UseCase\SyncBankAccounts;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:sync',
    description: 'Pull movements from the connected banks',
)]
class SyncBanksCommand extends Command
{
    public function __construct(
        private readonly SyncBankAccounts $syncBankAccounts,
        private readonly UserRepository $userRepository,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addArgument('email', InputArgument::REQUIRED, 'Email of the account owner')
            ->addOption('write', 'w', InputOption::VALUE_NONE, 'Actually write: without it the sync only reports')
            ->setHelp(<<<'HELP'
                Fetches what the connected banks have, and files it on the
                matching accounts.

                Each fetch spends part of the bank's daily allowance — four a
                day is the common ceiling for calls made without the user
                present, which is what this command does. It rehearses by
                default; pass --write once the report looks right.

                  <info>%command.full_name% moi@example.com</info>
                  <info>%command.full_name% moi@example.com --write</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        $user = $this->userRepository->findOneBy(['email' => $input->getArgument('email')]);
        if ($user === null) {
            $io->error(sprintf('No user with email "%s".', $input->getArgument('email')));

            return Command::FAILURE;
        }

        $dryRun = !$input->getOption('write');

        try {
            $result = $this->syncBankAccounts->execute($user, $dryRun);
        } catch (\RuntimeException $e) {
            $io->error($e->getMessage());

            return Command::FAILURE;
        }

        if ($result['accounts'] === []) {
            $io->warning('No connected account: connect a bank from the admin first.');

            return Command::SUCCESS;
        }

        $io->table(
            ['Banque', 'Compte', 'État', 'Importées', 'Déjà là', 'Depuis'],
            array_map(static fn (array $row) => [
                $row['bankName'] ?? '—',
                $row['accountName'] ?? '—',
                $row['status'],
                (string) ($row['imported'] ?? '—'),
                (string) ($row['skipped'] ?? '—'),
                $row['from'] ?? '—',
            ], $result['accounts']),
        );

        foreach ($result['accounts'] as $row) {
            if (isset($row['message'])) {
                $io->warning(sprintf('%s : %s', $row['bankName'] ?? '', $row['message']));
            }
        }

        $io->definitionList(
            ['Mouvements importés' => $result['imported']],
            ['Déjà présents' => $result['skipped']],
            ['Appels au fournisseur' => $result['providerCalls']],
        );

        if ($dryRun) {
            $io->note('Rehearsal only — nothing was written. Pass --write to sync for real.');
        } else {
            $io->success('Synchronisation terminée.');
        }

        return Command::SUCCESS;
    }
}
