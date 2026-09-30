<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client;
use Psr\Log\LoggerInterface;

class SearchService
{
    public function __construct(
        private readonly Client $client,
        private readonly IndexableEntityRegistry $registry,
        private readonly IndexMetadataReader $metadataReader,
        private readonly EntityManagerInterface $em,
        private readonly LoggerInterface $logger,
    ) {
    }

    /**
     * @param string[]|null $indices
     *
     * @return array{total: int, results: array<int, array{index: string, id: string, score: float, data: array<string, mixed>, highlights: array<string, string[]>}>}
     */
    public function search(
        string $query,
        string $userId,
        ?array $indices = null,
        int $from = 0,
        int $size = 10,
    ): array {
        try {
            return $this->elasticsearchSearch($query, $userId, $indices, $from, $size);
        } catch (\Throwable $e) {
            $this->logger->warning('Elasticsearch unavailable, falling back to Doctrine: {message}', [
                'message' => $e->getMessage(),
            ]);

            return $this->doctrineSearch($query, $userId, $indices, $from, $size);
        }
    }

    /**
     * @param string[]|null $indices
     *
     * @return array{total: int, results: array<int, array{index: string, id: string, score: float, data: array<string, mixed>, highlights: array<string, string[]>}>}
     */
    private function elasticsearchSearch(
        string $query,
        string $userId,
        ?array $indices,
        int $from,
        int $size,
    ): array {
        $targetIndices = $indices ?? array_keys($this->registry->getAll());

        if ([] === $targetIndices) {
            return ['total' => 0, 'results' => []];
        }

        // Build boosted fields list from metadata
        $boostedFields = $this->getBoostedFields($targetIndices);

        $fuzzyMatch = [
            'multi_match' => [
                'query' => $query,
                'type' => 'best_fields',
                'fuzziness' => 'AUTO',
            ],
        ];
        if ([] !== $boostedFields) {
            $fuzzyMatch['multi_match']['fields'] = $boostedFields;
        }

        // Wildcard substring match (*query*) on all text fields
        $wildcardPattern = '*'.mb_strtolower($query).'*';
        $textFields = $this->getTextFields($targetIndices);
        $wildcardClauses = [];
        foreach ($textFields as $field) {
            $wildcardClauses[] = ['wildcard' => [$field => ['value' => $wildcardPattern]]];
        }

        $shouldClauses = [$fuzzyMatch];
        if ([] !== $wildcardClauses) {
            $shouldClauses[] = ['bool' => ['should' => $wildcardClauses]];
        }

        $body = [
            'query' => [
                'bool' => [
                    'must' => [
                        'bool' => [
                            'should' => $shouldClauses,
                        ],
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
     * Doctrine ILIKE fallback when Elasticsearch is unavailable.
     *
     * @param string[]|null $indices
     *
     * @return array{total: int, results: array<int, array{index: string, id: string, score: float, data: array<string, mixed>, highlights: array<string, string[]>}>}
     */
    private function doctrineSearch(
        string $query,
        string $userId,
        ?array $indices,
        int $from,
        int $size,
    ): array {
        $allEntities = $this->registry->getAll();
        $targetIndices = $indices ?? array_keys($allEntities);
        $results = [];

        $likePattern = '%'.str_replace(['%', '_'], ['\\%', '\\_'], mb_strtolower($query)).'%';

        foreach ($allEntities as $indexName => $entityClass) {
            if (!\in_array($indexName, $targetIndices, true)) {
                continue;
            }

            $meta = $this->metadataReader->read($entityClass);
            if (null === $meta) {
                continue;
            }

            $textFields = [];
            foreach ($meta['fields'] as $fieldName => $mapping) {
                if (($mapping['type'] ?? '') === 'text') {
                    $textFields[] = $fieldName;
                }
            }

            if ([] === $textFields) {
                continue;
            }

            $qb = $this->em->createQueryBuilder()
                ->select('e')
                ->from($entityClass, 'e');

            if (!$this->addUserScope($qb, $entityClass, $userId)) {
                continue;
            }

            // ILIKE on text fields
            $orConditions = [];
            foreach ($textFields as $i => $field) {
                $dqlField = $this->toDqlField($entityClass, $field);
                if (null === $dqlField) {
                    continue;
                }
                $orConditions[] = "LOWER({$dqlField}) LIKE :pattern_{$i}";
                $qb->setParameter("pattern_{$i}", $likePattern);
            }

            if ([] === $orConditions) {
                continue;
            }

            $qb->andWhere(implode(' OR ', $orConditions));

            $allMatches = $qb->getQuery()->getResult();

            foreach ($allMatches as $entity) {
                $doc = $entity->toSearchDocument();
                $results[] = [
                    'index' => $indexName,
                    'id' => (string) $entity->getId(),
                    'score' => 1.0,
                    'data' => $doc,
                    'highlights' => [],
                ];
            }
        }

        $total = \count($results);

        return [
            'total' => $total,
            'results' => \array_slice($results, $from, $size),
        ];
    }

    /**
     * Add user-scoping WHERE clause to the query builder.
     * Returns false if the entity cannot be scoped to a user.
     */
    private function addUserScope(\Doctrine\ORM\QueryBuilder $qb, string $entityClass, string $userId): bool
    {
        /** @var \Doctrine\ORM\Mapping\ClassMetadata<object> $classMetadata */
        $classMetadata = $this->em->getClassMetadata($entityClass); // @phpstan-ignore argument.templateType

        if ($classMetadata->hasAssociation('user')) {
            // Direct user relation (Task, Recipe, Product, Notification, etc.)
            $qb->join('e.user', 'u')
                ->andWhere('CAST(u.id AS TEXT) = :userId')
                ->setParameter('userId', $userId);

            return true;
        }

        if ($classMetadata->hasAssociation('agenda')) {
            // Scoped via agenda → user (Event, Meal)
            $qb->join('e.agenda', 'a')
                ->join('a.user', 'u')
                ->andWhere('CAST(u.id AS TEXT) = :userId')
                ->setParameter('userId', $userId);

            return true;
        }

        return false;
    }

    /**
     * Map an ES field name to a DQL-accessible expression.
     */
    private function toDqlField(string $entityClass, string $esField): ?string
    {
        /** @var \Doctrine\ORM\Mapping\ClassMetadata<object> $classMetadata */
        $classMetadata = $this->em->getClassMetadata($entityClass); // @phpstan-ignore argument.templateType

        if ($classMetadata->hasField($esField)) {
            return "e.{$esField}";
        }

        return null;
    }

    /**
     * Build fields list with boost notation (e.g. "summary^3") from entity metadata.
     *
     * @param string[] $targetIndices
     *
     * @return string[]
     */
    private function getBoostedFields(array $targetIndices): array
    {
        $fields = [];
        $allEntities = $this->registry->getAll();

        foreach ($allEntities as $indexName => $entityClass) {
            if ([] !== $targetIndices && !\in_array($indexName, $targetIndices, true)) {
                continue;
            }

            $meta = $this->metadataReader->read($entityClass);
            if (null === $meta) {
                continue;
            }

            foreach ($meta['fields'] as $fieldName => $mapping) {
                // Only boost text fields (searchable)
                if (($mapping['type'] ?? '') !== 'text') {
                    continue;
                }

                $boost = $meta['boosts'][$fieldName] ?? null;
                $key = null !== $boost ? "{$fieldName}^{$boost}" : $fieldName;

                if (!\in_array($key, $fields, true)) {
                    $fields[] = $key;
                }
            }
        }

        return $fields;
    }

    /**
     * Get plain text field names (without boost notation) for wildcard queries.
     *
     * @param string[] $targetIndices
     *
     * @return string[]
     */
    private function getTextFields(array $targetIndices): array
    {
        $fields = [];
        $allEntities = $this->registry->getAll();

        foreach ($allEntities as $indexName => $entityClass) {
            if ([] !== $targetIndices && !\in_array($indexName, $targetIndices, true)) {
                continue;
            }

            $meta = $this->metadataReader->read($entityClass);
            if (null === $meta) {
                continue;
            }

            foreach ($meta['fields'] as $fieldName => $mapping) {
                if (($mapping['type'] ?? '') !== 'text') {
                    continue;
                }

                if (!\in_array($fieldName, $fields, true)) {
                    $fields[] = $fieldName;
                }
            }
        }

        return $fields;
    }
}
