<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Elastic\Elasticsearch\Client;

final class IndexManager
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexMetadataReader $metadataReader,
        private readonly IndexableEntityRegistry $registry,
    ) {}

    public function createOrUpdateIndex(string $entityClass): void
    {
        $meta = $this->metadataReader->read($entityClass);
        if ($meta === null) {
            return;
        }

        $indexName = $meta['index'];
        $exists = $this->client->indices()->exists(['index' => $indexName])->asBool();

        if (!$exists) {
            $this->client->indices()->create([
                'index' => $indexName,
                'body' => [
                    'settings' => $this->getDefaultSettings(),
                    'mappings' => [
                        'properties' => $this->buildProperties($meta),
                    ],
                ],
            ]);
        } else {
            $this->client->indices()->putMapping([
                'index' => $indexName,
                'body' => [
                    'properties' => $this->buildProperties($meta),
                ],
            ]);
        }
    }

    public function deleteIndex(string $indexName): void
    {
        $exists = $this->client->indices()->exists(['index' => $indexName])->asBool();
        if ($exists) {
            $this->client->indices()->delete(['index' => $indexName]);
        }
    }

    public function createAllIndices(): void
    {
        foreach ($this->registry->getAll() as $entityClass) {
            $this->createOrUpdateIndex($entityClass);
        }
    }

    /** @param array<string, mixed> $document */
    public function indexDocument(string $indexName, string $id, array $document): void
    {
        $this->client->index([
            'index' => $indexName,
            'id' => $id,
            'body' => $document,
        ]);
    }

    public function deleteDocument(string $indexName, string $id): void
    {
        $this->client->delete([
            'index' => $indexName,
            'id' => $id,
        ]);
    }

    /**
     * @param array<int, array{index: string, id: string, document: array<string, mixed>}> $operations
     */
    public function bulkIndex(array $operations): void
    {
        if ($operations === []) {
            return;
        }

        $body = [];
        foreach ($operations as $op) {
            $body[] = ['index' => ['_index' => $op['index'], '_id' => $op['id']]];
            $body[] = $op['document'];
        }

        $this->client->bulk(['body' => $body]);
    }

    /**
     * @return array<string, array{docs_count: int, size: string}>
     */
    public function getIndicesStatus(): array
    {
        $result = [];
        $response = $this->client->cat()->indices(['format' => 'json'])->asArray();

        foreach ($response as $index) {
            if (str_starts_with($index['index'], '.')) {
                continue; // Skip system indices
            }
            $result[$index['index']] = [
                'docs_count' => (int) $index['docs.count'],
                'size' => $index['store.size'],
            ];
        }

        return $result;
    }

    /**
     * @param array{index: string, module: ?string, fields: array<string, array<string, mixed>>, relations: array<string, array{targetEntity: string, sourceField: string}>} $meta
     * @return array<string, array<string, mixed>>
     */
    private function buildProperties(array $meta): array
    {
        $properties = $meta['fields'];

        // Add userId field for scoping (always keyword)
        $properties['userId'] = ['type' => 'keyword'];

        // Add relation source fields as keyword
        foreach ($meta['relations'] as $rel) {
            $properties[$rel['sourceField']] = ['type' => 'keyword'];
        }

        return $properties;
    }

    /**
     * @return array<string, mixed>
     */
    private function getDefaultSettings(): array
    {
        return [
            'analysis' => [
                'filter' => [
                    'french_elision' => [
                        'type' => 'elision',
                        'articles_case' => true,
                        'articles' => ['l', 'm', 't', 'qu', 'n', 's', 'j', 'd', 'c', 'jusqu', 'quoiqu', 'lorsqu', 'puisqu'],
                    ],
                    'french_stemmer' => [
                        'type' => 'stemmer',
                        'language' => 'light_french',
                    ],
                ],
                'analyzer' => [
                    'french' => [
                        'tokenizer' => 'standard',
                        'filter' => ['french_elision', 'lowercase', 'french_stemmer'],
                    ],
                ],
            ],
        ];
    }
}
