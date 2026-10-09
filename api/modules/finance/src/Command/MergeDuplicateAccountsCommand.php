<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Finance\UseCase\MergeDuplicateAccounts;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:merge-duplicate-accounts',
    description: 'Merge the copies of a bank account that renewed consents created, and drop their duplicate movements',
)]
class MergeDuplicateAccountsCommand extends Command
{
    public function __construct(
        private readonly MergeDuplicateAccounts $mergeDuplicateAccounts,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('dry-run', null, InputOption::VALUE_NONE, 'Only report what would be merged, write nothing')
            ->setHelp(<<<'HELP'
                Until MAG-351, every renewed bank consent created a second copy of
                each account — Enable Banking names accounts with a new uid in
                every session — and the next sync read every movement again.

                This folds each set of copies into the oldest account: its
                movements, minus those it already holds, with the categories,
                flags and transfer pairings set on the copies. The copies are
                then deleted, and the lists and open screens are updated.

                Copies are recognised by their identification at the bank, read
                from the live session; those of expired sessions by connection,
                name, currency, and balance or shared movements. A lookalike
                that could be two real accounts is reported, never merged.
                Running it twice changes nothing more; every bank sync runs it
                for its owner.

                  <info>%command.full_name% --dry-run</info>
                  <info>%command.full_name%</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $result = $this->mergeDuplicateAccounts->execute($dryRun);

        foreach ($result['warnings'] as $warning) {
            $io->warning($warning);
        }

        if ([] === $result['groups']) {
            $io->writeln('Aucun compte en double.');
        } else {
            $io->table(
                ['Compte gardé', 'Copies', 'Mouvements repris', 'Doublons supprimés'],
                array_map(static fn (array $group) => [
                    sprintf('%s (%s)', $group['name'], $group['survivor']),
                    implode("\n", $group['merged']),
                    $group['moved'],
                    $group['dropped'],
                ], $result['groups']),
            );
        }

        if ([] !== $result['ambiguous']) {
            $io->warning(array_merge(
                ['Ressemblent à plusieurs comptes réels, laissés tels quels :'],
                $result['ambiguous'],
            ));
        }

        $io->definitionList(['Identifications apprises' => $result['keysLearnt']]);

        if ($dryRun) {
            $io->note('Dry run only — nothing was written.');

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d compte(s) fusionné(s).', array_sum(array_map(
            static fn (array $group) => \count($group['merged']),
            $result['groups'],
        ))));

        return Command::SUCCESS;
    }
}
