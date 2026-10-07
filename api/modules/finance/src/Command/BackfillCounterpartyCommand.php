<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:backfill-counterparty',
    description: 'Fill the counterparty of the transactions that have none, from their label',
)]
class BackfillCounterpartyCommand extends Command
{
    private const BATCH_SIZE = 100;

    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('dry-run', null, InputOption::VALUE_NONE, 'Only report what would be filled, write nothing')
            ->setHelp(<<<'HELP'
                Gives a counterparty to the transactions stored before it had a
                field of its own, reading it from the label like a CSV import does.

                A bank line the next sync re-reads gets the bank's own creditor or
                debtor name instead; this covers the rest. Lines whose label names
                no one are left as they are. Running it twice changes nothing more.

                  <info>%command.full_name% --dry-run</info>
                  <info>%command.full_name%</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $filled = 0;
        $left = 0;
        /** @var list<Transaction> $batch */
        $batch = [];

        foreach ($this->transactionRepository->iterateWithoutCounterparty() as $transaction) {
            $merchant = MerchantExtractor::extract($transaction->getLabel());
            if (null === $merchant || '' === MerchantExtractor::key($merchant)) {
                ++$left;
                continue;
            }

            ++$filled;
            if ($dryRun) {
                continue;
            }

            $transaction->setCounterpartyName($merchant);
            $batch[] = $transaction;

            if (\count($batch) >= self::BATCH_SIZE) {
                $this->flushBatch($batch);
                $batch = [];
            }
        }

        $this->flushBatch($batch);

        $io->definitionList(
            [($dryRun ? 'À renseigner' : 'Renseignées') => $filled],
            ['Sans contrepartie lisible' => $left],
        );

        if ($dryRun) {
            $io->note('Dry run only — nothing was written.');

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d transaction(s) mise(s) à jour.', $filled));

        return Command::SUCCESS;
    }

    /** @param list<Transaction> $batch */
    private function flushBatch(array $batch): void
    {
        if ([] === $batch) {
            return;
        }

        $this->em->flush();

        // Straight to the database, so nothing on the bus reindexes them: the
        // lists read Elasticsearch and would keep showing no counterparty.
        foreach ($batch as $transaction) {
            $this->broadcaster->broadcast($transaction);
        }

        $this->em->clear();
    }
}
