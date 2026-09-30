<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Import\CsvStatementParser;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:import',
    description: 'Import a bank CSV export into an account',
)]
class ImportStatementCommand extends Command
{
    public function __construct(
        private readonly CsvStatementParser $parser,
        private readonly ImportStatement $importStatement,
        private readonly AccountRepository $accountRepository,
        private readonly UserRepository $userRepository,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addArgument('file', InputArgument::REQUIRED, 'Path to the CSV export')
            ->addArgument('email', InputArgument::REQUIRED, 'Email of the account owner')
            ->addOption('account', 'a', InputOption::VALUE_REQUIRED, 'Account name or id to import into')
            ->addOption('write', 'w', InputOption::VALUE_NONE, 'Actually write: without it the import only reports what it would do')
            ->setHelp(<<<'HELP'
                Reads a bank CSV export and files its movements on one account.

                It runs as a rehearsal by default and writes nothing: pass --write
                once the report looks right. Re-importing the same file is harmless,
                movements already stored are recognised and skipped.

                  <info>%command.full_name% relevé.csv moi@example.com --account "Compte courant"</info>
                  <info>%command.full_name% relevé.csv moi@example.com --account "Compte courant" --write</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        $file = (string) $input->getArgument('file');
        if (!is_readable($file)) {
            $io->error(sprintf('Cannot read "%s".', $file));

            return Command::FAILURE;
        }

        $user = $this->userRepository->findOneBy(['email' => $input->getArgument('email')]);
        if (null === $user) {
            $io->error(sprintf('No user with email "%s".', $input->getArgument('email')));

            return Command::FAILURE;
        }

        $accounts = $this->accountRepository->findByUser($user);
        if ([] === $accounts) {
            $io->error('This user has no account yet — create one before importing.');

            return Command::FAILURE;
        }

        $wanted = $input->getOption('account');
        $account = null;

        foreach ($accounts as $candidate) {
            if (null !== $wanted
                && (mb_strtolower($candidate->getName()) === mb_strtolower((string) $wanted)
                    || (string) $candidate->getId() === $wanted)
            ) {
                $account = $candidate;
                break;
            }
        }

        if (null === $account) {
            $io->error(sprintf(
                'Name the account to import into with --account. Available: %s.',
                implode(', ', array_map(static fn ($a) => sprintf('"%s"', $a->getName()), $accounts)),
            ));

            return Command::FAILURE;
        }

        $parsed = $this->parser->parse((string) file_get_contents($file), $account->getCurrency());

        foreach ($parsed['errors'] as $error) {
            $io->warning($error);
        }

        if ([] === $parsed['rows']) {
            $io->error('No movement could be read from this file.');

            return Command::FAILURE;
        }

        $dryRun = !$input->getOption('write');
        $result = $this->importStatement->execute($account, $parsed['rows'], $dryRun);

        $io->definitionList(
            ['Compte' => sprintf('%s (%s)', $account->getName(), $account->getCurrency())],
            ['Lignes lues' => \count($parsed['rows'])],
            ['À importer' => $result['imported']],
            ['Déjà présentes' => $result['skipped']],
            ['Catégorisées par règle' => $result['categorized']],
            ['Période' => null === $result['first']
                ? '—'
                : sprintf('%s → %s', $result['first'], $result['last'])],
            ['Solde des mouvements' => sprintf('%.2f %s', $result['totalCents'] / 100, $account->getCurrency())],
        );

        if ($dryRun) {
            $io->note('Rehearsal only — nothing was written. Pass --write to import for real.');

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d mouvement(s) importé(s).', $result['imported']));

        return Command::SUCCESS;
    }
}
