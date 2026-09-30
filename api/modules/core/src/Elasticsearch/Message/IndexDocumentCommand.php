<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Message;

final readonly class IndexDocumentCommand
{
    public function __construct(
        public string $entityClass,
        public string $entityId,
    ) {
    }
}
