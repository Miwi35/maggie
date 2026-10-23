<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

use Elastic\Elasticsearch\Client;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;

final class ElasticsearchLoadedObjectsIndexer implements LoadedObjectsIndexer
{
    public function __construct(
        private readonly IndexManager $indexManager,
        private readonly IndexMetadataReader $metadataReader,
        private readonly Client $client,
    ) {
    }

    public function index(array $objects): void
    {
        $operations = [];
        $indices = [];

        foreach ($objects as $object) {
            if (!$object instanceof IndexableInterface) {
                continue;
            }

            // A Meal is a document of `meals` only, as the reindex has it.
            $index = $this->metadataReader->indexOf($object::class);
            if (null === $index) {
                continue;
            }

            $operations[] = ['index' => $index, 'id' => (string) $object->getId(), 'document' => $object->toSearchDocument()];
            $indices[$index] = true;
        }

        $this->indexManager->bulkIndex($operations);

        // Indexed is not findable: Elasticsearch refreshes about once a second,
        // and the journey's first read follows this answer immediately.
        if ([] !== $indices) {
            $this->client->indices()->refresh(['index' => implode(',', array_keys($indices))]);
        }
    }
}
