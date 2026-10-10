<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProviderInterface;
use Elastic\Elasticsearch\Client;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Pagination\ElasticsearchPaginator;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Psr\Log\LoggerInterface;
use Symfony\Bundle\SecurityBundle\Security;

/**
 * @implements ProviderInterface<object>
 */
final class ElasticsearchCollectionProvider implements ProviderInterface
{
    /**
     * @param ProviderInterface<object> $doctrineProvider
     */
    public function __construct(
        private readonly Client $client,
        private readonly IndexMetadataReader $metadataReader,
        private readonly ElasticsearchEntityHydrator $hydrator,
        private readonly ElasticsearchFilterTranslator $filterTranslator,
        private readonly Security $security,
        private readonly LoggerInterface $logger,
        private readonly ProviderInterface $doctrineProvider,
        private readonly string $environment = 'prod',
    ) {
    }

    public function provide(Operation $operation, array $uriVariables = [], array $context = []): object|array|null
    {
        $entityClass = $operation->getClass();
        $meta = $this->metadataReader->read($entityClass);

        if (null === $meta || 'test' === $this->environment) {
            return $this->doctrineProvider->provide($operation, $uriVariables, $context);
        }

        try {
            return $this->doProvide($meta, $entityClass, $operation, $context);
        } catch (\Throwable $e) {
            // An error, not a warning: Doctrine serializes the whole entity,
            // Elasticsearch rebuilds it from the indexed document, so the same
            // URL answers with different fields depending on which one served
            // it. `event` is the stable key to search for.
            $this->logger->error('ES collection query failed, falling back to Doctrine: {error}', [
                'event' => 'es_collection_fallback',
                'error' => $e->getMessage(),
                'exception' => $e,
                'entity' => $entityClass,
                'index' => $meta['index'],
                'filters' => $context['filters'] ?? [],
            ]);

            return $this->doctrineProvider->provide($operation, $uriVariables, $context);
        }
    }

    /**
     * @param array{index: string, module: ?string, fields: array<string, mixed>, relations: array<string, mixed>, dayFields?: array<string, string>} $meta
     * @param array<string, mixed>                                                                                                                    $context
     */
    private function doProvide(array $meta, string $entityClass, Operation $operation, array $context): ElasticsearchPaginator
    {
        $userId = $this->getCurrentUserId();
        $filters = $context['filters'] ?? [];
        $translated = $this->filterTranslator->translate($filters, $meta['fields'], $meta['relations'], $meta['dayFields'] ?? []);

        // Pagination
        $page = (int) ($filters['page'] ?? 1);
        $itemsPerPage = (int) ($filters['itemsPerPage'] ?? $operation->getPaginationItemsPerPage() ?? 30);
        $from = ($page - 1) * $itemsPerPage;

        // Build query
        $boolQuery = [
            'filter' => array_merge(
                [['term' => ['userId' => $userId]]],
                $translated['filter'],
            ),
        ];

        if ([] !== $translated['must']) {
            $boolQuery['must'] = $translated['must'];
        }

        $body = [
            'query' => ['bool' => $boolQuery],
            'from' => $from,
            'size' => $itemsPerPage,
        ];

        // Sort
        if ([] !== $translated['sort']) {
            $body['sort'] = $translated['sort'];
        } elseif (null !== $operation->getOrder()) {
            $body['sort'] = [];
            foreach ($operation->getOrder() as $field => $direction) {
                $body['sort'][] = [$field => strtolower($direction)];
            }
        }

        $response = $this->client->search([
            'index' => $meta['index'],
            'body' => $body,
        ])->asArray();

        $items = [];
        foreach ($response['hits']['hits'] as $hit) {
            $source = $hit['_source'];
            $source['id'] = $hit['_id'];
            $items[] = $this->hydrator->hydrate($source, $entityClass);
        }

        $total = $response['hits']['total']['value'];

        return new ElasticsearchPaginator($items, (float) $page, (float) $itemsPerPage, (float) $total);
    }

    private function getCurrentUserId(): string
    {
        $user = $this->security->getUser();

        if ($user instanceof \Maggie\Core\Entity\User) {
            return (string) $user->getId();
        }

        throw new \RuntimeException('No authenticated user');
    }
}
