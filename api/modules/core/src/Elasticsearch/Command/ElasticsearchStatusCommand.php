<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Command;

use Elastic\Elasticsearch\Client;
use Maggie\Core\Elasticsearch\IndexManager;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:elasticsearch:status',
    description: 'Show Elasticsearch index status',
)]
final class ElasticsearchStatusCommand extends Command
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexManager $indexManager,
    ) {
        parent::__construct();
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
            $io->error('Cannot connect to Elasticsearch: ' . $e->getMessage());
            return Command::FAILURE;
        }

        $indices = $this->indexManager->getIndicesStatus();

        if ($indices === []) {
            $io->note('No indices found');
            return Command::SUCCESS;
        }

        $io->section('Indices');
        $rows = [];
        foreach ($indices as $name => $info) {
            $rows[] = [$name, $info['docs_count'], $info['size']];
        }

        $io->table(['Index', 'Documents', 'Size'], $rows);

        return Command::SUCCESS;
    }
}
