<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Message;

final readonly class DeleteDocumentCommand
{
    public function __construct(
        public string $indexName,
        public string $documentId,
    ) {
    }
}
