<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Command;

use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:elasticsearch:mapping:update',
    description: 'Create or update Elasticsearch index mappings',
)]
final class ElasticsearchMappingCommand extends Command
{
    public function __construct(
        private readonly IndexableEntityRegistry $registry,
        private readonly IndexManager $indexManager,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('all', null, InputOption::VALUE_NONE, 'Update all index mappings')
            ->addOption('entity', null, InputOption::VALUE_REQUIRED, 'Entity short name')
            ->addOption('module', null, InputOption::VALUE_REQUIRED, 'Module name');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $all = $input->getOption('all');
        $entityName = $input->getOption('entity');
        $moduleName = $input->getOption('module');

        if (!$all && $entityName === null && $moduleName === null) {
            $io->error('Specify --all, --entity, or --module');
            return Command::FAILURE;
        }

        $entities = $all
            ? $this->registry->getAll()
            : ($moduleName !== null
                ? $this->registry->getByModule($moduleName)
                : $this->findByName($entityName));

        if ($entities === []) {
            $io->warning('No indexable entities found');
            return Command::SUCCESS;
        }

        foreach ($entities as $indexName => $entityClass) {
            $io->text("Updating mapping for <comment>{$indexName}</comment> ({$entityClass})");
            $this->indexManager->createOrUpdateIndex($entityClass);
        }

        $io->success(\count($entities) . ' index mapping(s) updated');

        return Command::SUCCESS;
    }

    /**
     * @return array<string, class-string>
     */
    private function findByName(?string $entityName): array
    {
        if ($entityName === null) {
            return [];
        }

        foreach ($this->registry->getAll() as $indexName => $entityClass) {
            $shortName = substr($entityClass, strrpos($entityClass, '\\') + 1);
            if (strcasecmp($shortName, $entityName) === 0) {
                return [$indexName => $entityClass];
            }
        }

        return [];
    }
}
