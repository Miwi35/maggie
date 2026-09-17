<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Finance\Bank\EnableBanking\EnableBankingClient;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:banks',
    description: 'List the banks reachable through the bank data provider',
)]
class ListBanksCommand extends Command
{
    public function __construct(
        private readonly EnableBankingClient $client,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('country', 'c', InputOption::VALUE_REQUIRED, 'Two-letter country code', 'FR')
            ->addOption('search', 's', InputOption::VALUE_REQUIRED, 'Only show banks whose name contains this')
            ->setHelp(<<<'HELP'
                Asks the provider which banks it can reach, which is also the
                quickest way to prove the credentials and the request signing
                are working.

                  <info>%command.full_name% --country FR --search "crédit agricole"</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $country = (string) $input->getOption('country');

        try {
            $banks = $this->client->listBanks($country);
        } catch (\RuntimeException $e) {
            $io->error($e->getMessage());

            return Command::FAILURE;
        }

        $search = $input->getOption('search');
        if ($search !== null) {
            $needle = mb_strtolower((string) $search);
            $banks = array_values(array_filter(
                $banks,
                static fn (array $bank) => str_contains(mb_strtolower((string) ($bank['name'] ?? '')), $needle),
            ));
        }

        if ($banks === []) {
            $io->warning(sprintf('No bank found for %s.', strtoupper($country)));

            return Command::SUCCESS;
        }

        $io->table(
            ['Nom', 'Pays', 'Identifiant', 'Sandbox'],
            array_map(static fn (array $bank) => [
                $bank['name'] ?? '—',
                $bank['country'] ?? '—',
                implode(', ', (array) ($bank['psu_types'] ?? [])) ?: '—',
                ($bank['sandbox'] ?? false) ? 'oui' : 'non',
            ], $banks),
        );

        $io->success(sprintf('%d banque(s) accessible(s) en %s.', \count($banks), strtoupper($country)));

        return Command::SUCCESS;
    }
}
