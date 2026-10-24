<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine\Filter;

use ApiPlatform\Doctrine\Orm\Filter\FilterInterface;
use ApiPlatform\Doctrine\Orm\Util\QueryNameGeneratorInterface;
use ApiPlatform\Metadata\Operation;
use Doctrine\ORM\QueryBuilder;
use Maggie\Grocery\Entity\ProductAisle;
use Maggie\Grocery\Query\ProductShelfRequest;
use Maggie\Grocery\Query\ProductShelfRequestReader;

/**
 * The products of a store and of one of its aisles, and their sort (MAG-604),
 * for the engine that answers when Elasticsearch does not: the tests, the
 * ingredients, and any search that fell back.
 * {@see \Maggie\Grocery\Elasticsearch\ProductShelfSearchFilter} says the same
 * thing to Elasticsearch.
 *
 * `store`: the products whose usual or fallback store it is, or that have an
 * aisle in it. `aisle`: those in this aisle; `aisle=none`, those with none in
 * the store. `order[name|aisle|stockState]`, in the order asked: the aisle sort
 * follows the way through the store, « Sans rayon » last whatever the direction.
 *
 * Takes the place of an OrderFilter on `name`: two filters sorting the same
 * query would apply the sorts in the order they are declared, not asked.
 */
final class ProductShelfFilter implements FilterInterface
{
    public function __construct(
        private readonly ProductShelfRequestReader $reader,
    ) {
    }

    public function apply(QueryBuilder $queryBuilder, QueryNameGeneratorInterface $queryNameGenerator, string $resourceClass, ?Operation $operation = null, array $context = []): void
    {
        $request = $this->reader->read($context['filters'] ?? []);
        $alias = $queryBuilder->getRootAliases()[0];

        if ($request->matchesNothing) {
            $queryBuilder->andWhere('1 = 0');

            return;
        }

        if (null !== $request->store) {
            $store = $queryNameGenerator->generateParameterName('shelfStore');
            $queryBuilder->setParameter($store, $request->store, 'ulid');

            if (null !== $request->aisle) {
                $aisle = $queryNameGenerator->generateParameterName('shelfAisle');
                $queryBuilder->setParameter($aisle, $request->aisle, 'ulid');
                $queryBuilder->andWhere(sprintf('EXISTS (%s)', self::assignment($queryNameGenerator, $alias, $store, $aisle)));
            } else {
                $queryBuilder->andWhere(sprintf(
                    '(%1$s.preferredStore = :%2$s OR %1$s.fallbackStore = :%2$s OR EXISTS (%3$s))',
                    $alias,
                    $store,
                    self::assignment($queryNameGenerator, $alias, $store),
                ));
            }

            if ($request->withoutAisle) {
                $queryBuilder->andWhere(sprintf('NOT EXISTS (%s)', self::assignment($queryNameGenerator, $alias, $store)));
            }
        }

        foreach ($request->order as ['field' => $field, 'direction' => $direction]) {
            match ($field) {
                'aisle' => $this->orderByAisle($queryBuilder, $queryNameGenerator, $alias, $request, strtoupper($direction)),
                default => $queryBuilder->addOrderBy(sprintf('%s.%s', $alias, $field), strtoupper($direction)),
            };
        }
    }

    /** @return array<string, array<string, mixed>> */
    public function getDescription(string $resourceClass): array
    {
        $description = [
            'store' => [
                'property' => 'store',
                'type' => 'string',
                'required' => false,
                'description' => 'Only the products of this store: its usual or fallback store, or one with an aisle in it. Given as its IRI.',
                'openapi' => ['example' => '/api/stores/01ARZ3NDEKTSV4RRFFQ69G5FAV'],
            ],
            'aisle' => [
                'property' => 'aisle',
                'type' => 'string',
                'required' => false,
                'description' => 'Only the products in this aisle of the store (an IRI), or `none` for those with no aisle in it. Needs `store`; an aisle of another store is a 400.',
                'openapi' => ['example' => '/api/aisles/01ARZ3NDEKTSV4RRFFQ69G5FAV'],
            ],
        ];

        foreach (ProductShelfRequest::SORTS as $field) {
            $description[sprintf('order[%s]', $field)] = [
                'property' => $field,
                'type' => 'string',
                'required' => false,
                'schema' => ['type' => 'string', 'enum' => ['asc', 'desc']],
            ];
        }

        return $description;
    }

    private function orderByAisle(QueryBuilder $queryBuilder, QueryNameGeneratorInterface $queryNameGenerator, string $alias, ProductShelfRequest $request, string $direction): void
    {
        if (null === $request->store) {
            return;
        }

        $store = $queryNameGenerator->generateParameterName('shelfOrderStore');
        $queryBuilder->setParameter($store, $request->store, 'ulid');
        $position = sprintf(
            'SELECT %2$s.position FROM %1$s %3$s JOIN %3$s.aisle %2$s WHERE %3$s.product = %4$s AND %3$s.store = :%5$s',
            ProductAisle::class,
            $queryNameGenerator->generateJoinAlias('shelfAisle'),
            $queryNameGenerator->generateJoinAlias('shelfAssignment'),
            $alias,
            $store,
        );

        $count = sprintf(
            'SELECT COUNT(%2$s.id) FROM %1$s %2$s WHERE %2$s.product = %3$s AND %2$s.store = :%4$s',
            ProductAisle::class,
            $queryNameGenerator->generateJoinAlias('shelfCounted'),
            $alias,
            $store,
        );

        // DQL has no CASE over a subquery: the count (1 or 0) puts « Sans rayon » last.
        $queryBuilder
            ->addSelect(sprintf('(%s) AS HIDDEN shelfHasAisle', $count))
            ->addSelect(sprintf('(%s) AS HIDDEN shelfAislePosition', $position))
            ->addOrderBy('shelfHasAisle', 'DESC')
            ->addOrderBy('shelfAislePosition', $direction);
    }

    private static function assignment(QueryNameGeneratorInterface $queryNameGenerator, string $alias, string $store, ?string $aisle = null): string
    {
        $assignment = $queryNameGenerator->generateJoinAlias('shelfAssignment');

        return sprintf(
            'SELECT %2$s.id FROM %1$s %2$s WHERE %2$s.product = %3$s AND %2$s.store = :%4$s%5$s',
            ProductAisle::class,
            $assignment,
            $alias,
            $store,
            null !== $aisle ? sprintf(' AND %s.aisle = :%s', $assignment, $aisle) : '',
        );
    }
}
