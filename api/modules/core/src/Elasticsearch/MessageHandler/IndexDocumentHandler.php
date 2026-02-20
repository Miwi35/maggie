<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class IndexDocumentHandler
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly IndexManager $indexManager,
        private readonly IndexMetadataReader $metadataReader,
        private readonly LoggerInterface $logger,
    ) {}

    public function __invoke(IndexDocumentCommand $command): void
    {
        /** @var class-string $entityClass */
        $entityClass = $command->entityClass;
        $entity = $this->em->find($entityClass, $command->entityId);

        if ($entity === null) {
            $this->logger->warning('Entity not found for ES indexation (may have been deleted)', [
                'class' => $command->entityClass,
                'id' => $command->entityId,
            ]);
            return;
        }

        if (!$entity instanceof IndexableInterface) {
            return;
        }

        $meta = $this->metadataReader->read($command->entityClass);
        if ($meta === null) {
            return;
        }

        try {
            $this->indexManager->indexDocument(
                $meta['index'],
                (string) $entity->getId(),
                $entity->toSearchDocument(),
            );
        } catch (\Throwable $e) {
            $this->logger->error('Failed to index document in ES: {error}', [
                'error' => $e->getMessage(),
                'class' => $command->entityClass,
                'id' => $command->entityId,
            ]);
        }
    }
}
