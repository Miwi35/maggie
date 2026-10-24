<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Query;

use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * Filters and sorts of a resource that no indexed field carries by that name:
 * « the products of this store » is neither a column nor a relation of the
 * product, yet the clients ask for it with `?store=` like any other filter.
 *
 * The generic translator would turn `store` into a term query on a field the
 * mapping does not declare — an empty list, silently. A domain filter owns its
 * parameters instead, so the same request means the same thing whether
 * Elasticsearch or Doctrine answers it.
 *
 * An implementation throws an HttpException (400) for a request that makes no
 * sense; the collection provider lets it through instead of falling back to
 * Doctrine, which would only throw it again.
 */
#[AutoconfigureTag('maggie.es_domain_filter')]
interface ElasticsearchDomainFilter
{
    /** @param class-string $entityClass */
    public function supports(string $entityClass): bool;

    /**
     * The query parameters it answers, as spelt on the wire (`store`, `aisle`).
     *
     * @return list<string>
     */
    public function parameters(): array;

    /**
     * The `order[…]` fields it answers, in place of the generic sort.
     *
     * @return list<string>
     */
    public function sortFields(): array;

    /**
     * @param array<string, mixed> $filters the whole query
     *
     * @return list<array<string, mixed>> clauses to AND with the others
     */
    public function clauses(array $filters): array;

    /**
     * @param array<string, mixed> $filters the whole query
     *
     * @return array<string, mixed> one entry of the `sort` list
     */
    public function sort(string $field, string $direction, array $filters): array;
}
