<?php

declare(strict_types=1);

namespace Maggie\Grocery\Elasticsearch;

use Maggie\Core\Elasticsearch\Query\ElasticsearchDomainFilter;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Query\ProductShelfRequestReader;

/**
 * The products of a store and of one of its aisles, as Elasticsearch answers
 * them (MAG-604): the usual and fallback stores are indexed on the product, its
 * aisles as nested `aisles: [{storeId, aisleId, aislePosition}]` so that a store
 * and its aisle position are read together.
 * {@see \Maggie\Grocery\Doctrine\Filter\ProductShelfFilter} says the same to Doctrine.
 */
final class ProductShelfSearchFilter implements ElasticsearchDomainFilter
{
    public function __construct(
        private readonly ProductShelfRequestReader $reader,
    ) {
    }

    public function supports(string $entityClass): bool
    {
        return is_a($entityClass, Product::class, true);
    }

    public function parameters(): array
    {
        return ['store', 'aisle'];
    }

    public function sortFields(): array
    {
        return ['aisle'];
    }

    public function clauses(array $filters): array
    {
        $request = $this->reader->read($filters);

        if ($request->matchesNothing) {
            return [['ids' => ['values' => []]]];
        }
        if (null === $request->store) {
            return [];
        }

        $store = (string) $request->store;
        $clauses = [];

        if (null !== $request->aisle) {
            $clauses[] = self::nested(['term' => ['aisles.aisleId' => (string) $request->aisle]]);
        } else {
            $clauses[] = ['bool' => [
                'should' => [
                    ['term' => ['preferredStoreId' => $store]],
                    ['term' => ['fallbackStoreId' => $store]],
                    self::nested(['term' => ['aisles.storeId' => $store]]),
                ],
                'minimum_should_match' => 1,
            ]];
        }

        if ($request->withoutAisle) {
            $clauses[] = ['bool' => ['must_not' => [self::nested(['term' => ['aisles.storeId' => $store]])]]];
        }

        return $clauses;
    }

    public function sort(string $field, string $direction, array $filters): array
    {
        $request = $this->reader->read($filters);

        // « Sans rayon » closes the store whichever way the aisles run.
        return ['aisles.aislePosition' => [
            'order' => 'desc' === $direction ? 'desc' : 'asc',
            'missing' => '_last',
            'nested' => [
                'path' => 'aisles',
                'filter' => ['term' => ['aisles.storeId' => (string) $request->store]],
            ],
        ]];
    }

    /**
     * @param array<string, mixed> $query
     *
     * @return array<string, mixed>
     */
    private static function nested(array $query): array
    {
        return ['nested' => ['path' => 'aisles', 'query' => $query]];
    }
}
