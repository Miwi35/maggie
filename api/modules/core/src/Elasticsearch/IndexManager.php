<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Elastic\Elasticsearch\Client;
use Elastic\Elasticsearch\Exception\ClientResponseException;
use Psr\Log\LoggerInterface;

final class IndexManager
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexMetadataReader $metadataReader,
        private readonly IndexableEntityRegistry $registry,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function createOrUpdateIndex(string $entityClass): void
    {
        $meta = $this->metadataReader->read($entityClass);
        if (null === $meta) {
            return;
        }

        $indexName = $meta['index'];
        $exists = $this->client->indices()->exists(['index' => $indexName])->asBool();

        if (!$exists) {
            $this->createIndex($indexName, $meta);

            return;
        }

        try {
            $this->client->indices()->putMapping([
                'index' => $indexName,
                'body' => [
                    'properties' => $this->buildProperties($meta),
                ],
            ]);
        } catch (ClientResponseException $e) {
            // A field a document carried before the mapping declared it was
            // mapped dynamically (an `id` written by a new pod before the
            // deploy's mapping update ran becomes `text`), and Elasticsearch
            // never changes the type of a mapped field. The index is derived
            // from the database: rebuild it, the reindex that follows refills it.
            if (!str_contains($e->getMessage(), 'cannot be changed from type')) {
                throw $e;
            }

            $this->logger->warning('ES mapping of {index} conflicts with its dynamic mapping, recreating the index: reindex it ({error})', [
                'index' => $indexName,
                'error' => $e->getMessage(),
            ]);

            $this->deleteIndex($indexName);
            $this->createIndex($indexName, $meta);
        }
    }

    /**
     * @param array{index: string, module: ?string, fields: array<string, array<string, mixed>>, relations: array<string, array{targetEntity: string, sourceField: string}>} $meta
     */
    private function createIndex(string $indexName, array $meta): void
    {
        $this->client->indices()->create([
            'index' => $indexName,
            'body' => [
                'settings' => $this->getDefaultSettings(),
                'mappings' => [
                    'properties' => $this->buildProperties($meta),
                ],
            ],
        ]);
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

    /**
     * Refreshed on return: the web client refetches its list the moment a write answers, and a
     * search only sees refreshed documents (every second by default).
     *
     * @param array<string, mixed> $document
     */
    public function indexDocument(string $indexName, string $id, array $document): void
    {
        $this->client->index([
            'index' => $indexName,
            'id' => $id,
            'body' => self::withId($id, $document),
            'refresh' => 'true',
        ]);
    }

    public function deleteDocument(string $indexName, string $id): void
    {
        $this->client->delete([
            'index' => $indexName,
            'id' => $id,
            'refresh' => 'true',
        ]);
    }

    /**
     * Every document id the index holds, including those written a moment ago:
     * the index is refreshed first, since a search only sees refreshed documents.
     * A missing index holds none.
     *
     * @return list<string>
     */
    public function documentIds(string $indexName): array
    {
        try {
            $this->client->indices()->refresh(['index' => $indexName]);
            $page = $this->client->search([
                'index' => $indexName,
                'scroll' => '1m',
                'body' => ['size' => 1000, '_source' => false, 'query' => ['match_all' => new \stdClass()]],
            ])->asArray();
        } catch (ClientResponseException $e) {
            if (404 === $e->getCode()) {
                return [];
            }

            throw $e;
        }

        $ids = [];
        $scrollId = $page['_scroll_id'] ?? null;
        try {
            while ([] !== $page['hits']['hits']) {
                foreach ($page['hits']['hits'] as $hit) {
                    $ids[] = (string) $hit['_id'];
                }
                if (null === $scrollId) {
                    break;
                }
                $page = $this->client->scroll(['body' => ['scroll' => '1m', 'scroll_id' => $scrollId]])->asArray();
                $scrollId = $page['_scroll_id'] ?? $scrollId;
            }
        } finally {
            if (null !== $scrollId) {
                $this->client->clearScroll(['body' => ['scroll_id' => $scrollId]]);
            }
        }

        return $ids;
    }

    /**
     * @param array<int, array{index: string, id: string, document: array<string, mixed>}> $operations
     */
    public function bulkIndex(array $operations): void
    {
        if ([] === $operations) {
            return;
        }

        $body = [];
        foreach ($operations as $op) {
            $body[] = ['index' => ['_index' => $op['index'], '_id' => $op['id']]];
            $body[] = self::withId($op['id'], $op['document']);
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
     *
     * @return array<string, array<string, mixed>>
     */
    private function buildProperties(array $meta): array
    {
        $properties = $meta['fields'];

        // Add userId field for scoping (always keyword)
        $properties['userId'] = ['type' => 'keyword'];

        // The identifier as a field of its own, see withId()
        $properties['id'] = ['type' => 'keyword'];

        // Add relation source fields as keyword
        foreach ($meta['relations'] as $rel) {
            $properties[$rel['sourceField']] = ['type' => 'keyword'];
        }

        return $properties;
    }

    /**
     * The document's `_id` is not a field: Elasticsearch 8 refuses to sort on
     * it ("No mapping found for [id] in order to sort on"), and react-admin's
     * default list sort is `id`. Copying it into a keyword field gives that
     * sort something to stand on. ULIDs sort by creation time.
     *
     * @param array<string, mixed> $document
     *
     * @return array<string, mixed>
     */
    private static function withId(string $id, array $document): array
    {
        $document['id'] = $id;

        return $document;
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
