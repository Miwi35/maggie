<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\MessageHandler;

use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
final class DeleteDocumentHandler
{
    public function __construct(
        private readonly IndexManager $indexManager,
        private readonly LoggerInterface $logger,
    ) {}

    public function __invoke(DeleteDocumentCommand $command): void
    {
        try {
            $this->indexManager->deleteDocument($command->indexName, $command->documentId);
        } catch (\Throwable $e) {
            $this->logger->warning('Failed to delete ES document (may not exist): {error}', [
                'error' => $e->getMessage(),
                'index' => $command->indexName,
                'id' => $command->documentId,
            ]);
        }
    }
}
