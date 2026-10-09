<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Finance\UseCase\RepairAccountCurrencies;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:repair-account-currencies',
    description: 'Give a real currency to the accounts a bank left with « XXX »',
)]
class RepairAccountCurrenciesCommand extends Command
{
    public function __construct(
        private readonly RepairAccountCurrencies $repairAccountCurrencies,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('dry-run', null, InputOption::VALUE_NONE, 'Only list the accounts, write nothing')
            ->setHelp(<<<'HELP'
                Until MAG-376, a bank that answered « XXX » (ISO 4217 for « no
                currency ») gave its account that currency, and the totals and
                the matching of accounts went wrong with it.

                This lists every account whose currency is not a real one and
                gives it the currency its movements are mostly in, else the
                euro. An account with a real currency is never touched. None
                listed means all is well; running it twice changes nothing more.

                  <info>%command.full_name% --dry-run</info>
                  <info>%command.full_name%</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $repaired = $this->repairAccountCurrencies->execute($dryRun);

        if ([] === $repaired) {
            $io->success('Aucun compte sans devise réelle.');

            return Command::SUCCESS;
        }

        $io->table(
            ['Compte', 'Nom', 'Devise', 'Devenue'],
            array_map(static fn (array $row) => [$row['id'], $row['name'], $row['from'], $row['to']], $repaired),
        );

        if ($dryRun) {
            $io->note('Dry run only — nothing was written.');

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d compte(s) corrigé(s).', \count($repaired)));

        return Command::SUCCESS;
    }
}
