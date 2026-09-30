<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Command;

use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:elasticsearch:status',
    description: 'Show Elasticsearch index status and check DB/ES coherence',
)]
final class ElasticsearchStatusCommand extends Command
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexManager $indexManager,
        private readonly IndexableEntityRegistry $registry,
        private readonly EntityManagerInterface $em,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption(
            'check',
            null,
            InputOption::VALUE_NONE,
            'Exit with a non-zero code when an index is missing or drifts from the database (for health checks)',
        );
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        try {
            $health = $this->client->cluster()->health()->asArray();
            $io->section('Cluster Health');
            $io->table(
                ['Status', 'Nodes', 'Active Shards'],
                [[$health['status'], $health['number_of_nodes'], $health['active_primary_shards']]],
            );
        } catch (\Throwable $e) {
            $io->error('Cannot connect to Elasticsearch: '.$e->getMessage());

            return Command::FAILURE;
        }

        $indices = $this->indexManager->getIndicesStatus();

        if ([] !== $indices) {
            $io->section('Indices');
            $rows = [];
            foreach ($indices as $name => $info) {
                $rows[] = [$name, $info['docs_count'], $info['size']];
            }
            $io->table(['Index', 'Documents', 'Size'], $rows);
        } else {
            $io->note('No indices found');
        }

        return $this->renderCoherence($io, (bool) $input->getOption('check'));
    }

    /**
     * Compare the row count in the database against the document count in
     * Elasticsearch for every indexable entity. A drift (or a missing index)
     * means the search-backed collection endpoints will silently return stale
     * or empty results, since they only fall back to Doctrine on an exception.
     */
    private function renderCoherence(SymfonyStyle $io, bool $check): int
    {
        $entities = $this->registry->getAll();

        if ([] === $entities) {
            return Command::SUCCESS;
        }

        $io->section('Coherence (DB vs ES)');

        $rows = [];
        $hasDrift = false;

        foreach ($entities as $indexName => $entityClass) {
            $dbCount = (int) $this->em->createQueryBuilder()
                ->select('COUNT(e.id)')
                ->from($entityClass, 'e')
                ->getQuery()
                ->getSingleScalarResult();

            try {
                $esCount = (int) $this->client->count(['index' => $indexName])->asArray()['count'];
                $status = $esCount === $dbCount ? '<info>OK</info>' : '<comment>DRIFT</comment>';
                if ($esCount !== $dbCount) {
                    $hasDrift = true;
                }
                $esDisplay = (string) $esCount;
            } catch (\Throwable) {
                $status = '<error>MISSING INDEX</error>';
                $hasDrift = true;
                $esDisplay = '-';
            }

            $rows[] = [$indexName, $dbCount, $esDisplay, $status];
        }

        $io->table(['Index', 'DB', 'ES', 'Status'], $rows);

        if ($hasDrift) {
            $io->warning('Index drift detected — run "app:elasticsearch:reindex --all" and check the messenger worker.');

            return $check ? Command::FAILURE : Command::SUCCESS;
        }

        $io->success('Database and Elasticsearch are in sync.');

        return Command::SUCCESS;
    }
}
