<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Elastic\Elasticsearch\Client;

class SearchService
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexableEntityRegistry $registry,
        private readonly IndexMetadataReader $metadataReader,
    ) {}

    /**
     * @param string[]|null $indices
     * @return array{total: int, results: array<int, array{index: string, id: string, score: float, data: array<string, mixed>, highlights: array<string, string[]>}>}
     */
    public function search(
        string $query,
        string $userId,
        ?array $indices = null,
        int $from = 0,
        int $size = 10,
    ): array {
        $targetIndices = $indices ?? array_keys($this->registry->getAll());

        if ($targetIndices === []) {
            return ['total' => 0, 'results' => []];
        }

        // Build boosted fields list from metadata
        $boostedFields = $this->getBoostedFields($targetIndices);

        $multiMatch = [
            'query' => $query,
            'type' => 'best_fields',
            'fuzziness' => 'AUTO',
        ];
        if ($boostedFields !== []) {
            $multiMatch['fields'] = $boostedFields;
        }

        $body = [
            'query' => [
                'bool' => [
                    'must' => [
                        'multi_match' => $multiMatch,
                    ],
                    'filter' => [
                        'term' => ['userId' => $userId],
                    ],
                ],
            ],
            'highlight' => [
                'fields' => ['*' => new \stdClass()],
                'pre_tags' => ['<em>'],
                'post_tags' => ['</em>'],
            ],
            'from' => $from,
            'size' => $size,
        ];

        $response = $this->client->search([
            'index' => implode(',', $targetIndices),
            'body' => $body,
        ])->asArray();

        $results = [];
        foreach ($response['hits']['hits'] as $hit) {
            $results[] = [
                'index' => $hit['_index'],
                'id' => $hit['_id'],
                'score' => $hit['_score'],
                'data' => $hit['_source'],
                'highlights' => $hit['highlight'] ?? [],
            ];
        }

        return [
            'total' => $response['hits']['total']['value'],
            'results' => $results,
        ];
    }

    /**
     * Build fields list with boost notation (e.g. "summary^3") from entity metadata.
     *
     * @param string[] $targetIndices
     * @return string[]
     */
    private function getBoostedFields(array $targetIndices): array
    {
        $fields = [];
        $allEntities = $this->registry->getAll();

        foreach ($allEntities as $indexName => $entityClass) {
            if ($targetIndices !== [] && !\in_array($indexName, $targetIndices, true)) {
                continue;
            }

            $meta = $this->metadataReader->read($entityClass);
            if ($meta === null) {
                continue;
            }

            foreach ($meta['fields'] as $fieldName => $mapping) {
                // Only boost text fields (searchable)
                if (($mapping['type'] ?? '') !== 'text') {
                    continue;
                }

                $boost = $meta['boosts'][$fieldName] ?? null;
                $key = $boost !== null ? "{$fieldName}^{$boost}" : $fieldName;

                if (!\in_array($key, $fields, true)) {
                    $fields[] = $key;
                }
            }
        }

        return $fields;
    }
}
