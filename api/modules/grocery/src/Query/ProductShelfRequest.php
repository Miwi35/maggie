<?php

declare(strict_types=1);

namespace Maggie\Grocery\Query;

use Symfony\Component\Uid\Ulid;

/**
 * What a client asked of the products by store and aisle (MAG-604), checked once
 * for the two engines that answer it — Doctrine and Elasticsearch.
 */
final class ProductShelfRequest
{
    public const SORTS = ['name', 'aisle', 'stockState'];

    /**
     * @param list<array{field: string, direction: 'asc'|'desc'}> $order          the sorts of {@see self::SORTS}, in the order asked
     * @param bool                                                $matchesNothing the request names a store or an aisle that
     *                                                                            does not exist: an empty answer, never the
     *                                                                            whole collection
     */
    public function __construct(
        public readonly ?Ulid $store = null,
        public readonly ?Ulid $aisle = null,
        public readonly bool $withoutAisle = false,
        public readonly array $order = [],
        public readonly bool $matchesNothing = false,
    ) {
    }

    /** The direction asked for the sort on the aisle, null when it was not asked. */
    public function aisleDirection(): ?string
    {
        foreach ($this->order as $sort) {
            if ('aisle' === $sort['field']) {
                return $sort['direction'];
            }
        }

        return null;
    }
}
