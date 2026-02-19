<?php

declare(strict_types=1);

namespace Maggie\Core\Elasticsearch\Pagination;

use ApiPlatform\State\Pagination\PaginatorInterface;

/**
 * @implements \IteratorAggregate<int, object>
 */
final class ElasticsearchPaginator implements PaginatorInterface, \IteratorAggregate
{
    /**
     * @param object[] $items
     */
    public function __construct(
        private readonly array $items,
        private readonly float $currentPage,
        private readonly float $itemsPerPage,
        private readonly float $totalItems,
    ) {}

    public function getCurrentPage(): float
    {
        return $this->currentPage;
    }

    public function getItemsPerPage(): float
    {
        return $this->itemsPerPage;
    }

    public function getLastPage(): float
    {
        if ($this->itemsPerPage <= 0) {
            return 1.0;
        }

        return max(1.0, ceil($this->totalItems / $this->itemsPerPage));
    }

    public function getTotalItems(): float
    {
        return $this->totalItems;
    }

    public function count(): int
    {
        return \count($this->items);
    }

    public function getIterator(): \ArrayIterator
    {
        return new \ArrayIterator($this->items);
    }
}
