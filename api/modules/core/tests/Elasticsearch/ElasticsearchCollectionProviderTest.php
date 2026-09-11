<?php

namespace Maggie\Core\Tests\Elasticsearch;

use Maggie\Core\Elasticsearch\Pagination\ElasticsearchPaginator;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use PHPUnit\Framework\TestCase;

class ElasticsearchCollectionProviderTest extends TestCase
{
    public function testFilterTranslatorHandlesDateFilters(): void
    {
        $translator = new ElasticsearchFilterTranslator();
        // PHP parses ?startAt[after]=...&startAt[strictly_before]=... into nested arrays
        $result = $translator->translate([
            'startAt' => ['after' => '2026-01-01', 'strictly_before' => '2026-12-31'],
        ]);

        self::assertCount(2, $result['filter']);
        self::assertSame(['range' => ['startAt' => ['gte' => '2026-01-01']]], $result['filter'][0]);
        self::assertSame(['range' => ['startAt' => ['lt' => '2026-12-31']]], $result['filter'][1]);
    }

    public function testFilterTranslatorHandlesExistsFilter(): void
    {
        $translator = new ElasticsearchFilterTranslator();

        // PHP parses ?exists[rrule]=true into ['exists' => ['rrule' => 'true']]
        $result = $translator->translate(['exists' => ['rrule' => 'true']]);
        self::assertSame(['exists' => ['field' => 'rrule']], $result['filter'][0]);

        $result = $translator->translate(['exists' => ['rrule' => 'false']]);
        self::assertArrayHasKey('bool', $result['filter'][0]);
    }

    public function testFilterTranslatorHandlesSorting(): void
    {
        $translator = new ElasticsearchFilterTranslator();
        // PHP parses ?order[startAt]=asc&order[endAt]=DESC into nested array
        $result = $translator->translate(['order' => ['startAt' => 'asc', 'endAt' => 'DESC']]);

        self::assertCount(2, $result['sort']);
        self::assertSame(['startAt' => 'asc'], $result['sort'][0]);
        self::assertSame(['endAt' => 'desc'], $result['sort'][1]);
    }

    public function testFilterTranslatorHandlesTermFilter(): void
    {
        $translator = new ElasticsearchFilterTranslator();
        $result = $translator->translate(['priority' => 'high']);

        self::assertSame(['term' => ['priority' => 'high']], $result['filter'][0]);
    }

    public function testFilterTranslatorSkipsPaginationParams(): void
    {
        $translator = new ElasticsearchFilterTranslator();
        $result = $translator->translate(['page' => '2', 'itemsPerPage' => '10']);

        self::assertEmpty($result['filter']);
        self::assertEmpty($result['must']);
        self::assertEmpty($result['sort']);
    }

    public function testPaginatorReportsCorrectMetrics(): void
    {
        $paginator = new ElasticsearchPaginator(
            items: [new \stdClass(), new \stdClass()],
            currentPage: 2.0,
            itemsPerPage: 10.0,
            totalItems: 25.0,
        );

        self::assertSame(2.0, $paginator->getCurrentPage());
        self::assertSame(10.0, $paginator->getItemsPerPage());
        self::assertSame(25.0, $paginator->getTotalItems());
        self::assertSame(3.0, $paginator->getLastPage());
        self::assertCount(2, $paginator);
    }

    public function testPaginatorWithZeroItemsPerPage(): void
    {
        $paginator = new ElasticsearchPaginator(
            items: [],
            currentPage: 1.0,
            itemsPerPage: 0.0,
            totalItems: 0.0,
        );

        self::assertSame(1.0, $paginator->getLastPage());
    }

    public function testPaginatorIterator(): void
    {
        $a = new \stdClass();
        $b = new \stdClass();
        $paginator = new ElasticsearchPaginator([$a, $b], 1.0, 10.0, 2.0);

        $items = iterator_to_array($paginator);
        self::assertSame([$a, $b], $items);
    }
}
