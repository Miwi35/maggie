<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Helper\ProgressBar;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:elasticsearch:reindex',
    description: 'Reindex entities into Elasticsearch',
)]
final class ElasticsearchReindexCommand extends Command
{
    private const int BATCH_SIZE = 500;

    public function __construct(
        private readonly IndexableEntityRegistry $registry,
        private readonly IndexManager $indexManager,
        private readonly IndexMetadataReader $metadataReader,
        private readonly EntityManagerInterface $em,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('all', null, InputOption::VALUE_NONE, 'Reindex all entities')
            ->addOption('entity', null, InputOption::VALUE_REQUIRED, 'Entity short name (e.g. Event)')
            ->addOption('module', null, InputOption::VALUE_REQUIRED, 'Module name (e.g. calendar)')
            ->addOption('recreate', null, InputOption::VALUE_NONE, 'Delete and recreate indices before reindexing');
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

        $entities = $this->resolveEntities($all, $entityName, $moduleName);

        if ($entities === []) {
            $io->warning('No indexable entities found');
            return Command::SUCCESS;
        }

        foreach ($entities as $indexName => $entityClass) {
            $meta = $this->metadataReader->read($entityClass);
            if ($meta === null) {
                continue;
            }

            if ($input->getOption('recreate')) {
                $io->text("Deleting index <comment>{$indexName}</comment>...");
                $this->indexManager->deleteIndex($indexName);
            }

            $io->text("Creating/updating index <comment>{$indexName}</comment>...");
            $this->indexManager->createOrUpdateIndex($entityClass);

            $io->text("Reindexing <info>{$entityClass}</info> → <comment>{$indexName}</comment>");

            $count = (int) $this->em->createQueryBuilder()
                ->select('COUNT(e.id)')
                ->from($entityClass, 'e')
                ->getQuery()
                ->getSingleScalarResult();

            $progressBar = new ProgressBar($output, $count);
            $progressBar->start();

            $offset = 0;
            while ($offset < $count) {
                $entities_batch = $this->em->createQueryBuilder()
                    ->select('e')
                    ->from($entityClass, 'e')
                    ->setFirstResult($offset)
                    ->setMaxResults(self::BATCH_SIZE)
                    ->getQuery()
                    ->getResult();

                $operations = [];
                foreach ($entities_batch as $entity) {
                    if ($entity instanceof IndexableInterface) {
                        $operations[] = [
                            'index' => $indexName,
                            'id' => (string) $entity->getId(),
                            'document' => $entity->toSearchDocument(),
                        ];
                    }
                }

                if ($operations !== []) {
                    $this->indexManager->bulkIndex($operations);
                }

                $progressBar->advance(\count($entities_batch));
                $offset += self::BATCH_SIZE;
                $this->em->clear();
            }

            $progressBar->finish();
            $io->newLine();
        }

        $io->success('Reindexing complete');

        return Command::SUCCESS;
    }

    /**
     * @return array<string, class-string<IndexableInterface>>
     */
    private function resolveEntities(bool $all, ?string $entityName, ?string $moduleName): array
    {
        if ($all) {
            return $this->registry->getAll();
        }

        if ($moduleName !== null) {
            return $this->registry->getByModule($moduleName);
        }

        // Find by entity short name
        foreach ($this->registry->getAll() as $indexName => $entityClass) {
            $shortName = substr($entityClass, strrpos($entityClass, '\\') + 1);
            if (strcasecmp($shortName, $entityName) === 0) {
                return [$indexName => $entityClass];
            }
        }

        return [];
    }
}
