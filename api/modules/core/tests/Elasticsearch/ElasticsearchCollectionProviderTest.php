<?php

namespace Maggie\Core\Tests\Elasticsearch;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\State\ProviderInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Pagination\ElasticsearchPaginator;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;
use Symfony\Bundle\SecurityBundle\Security;

class ElasticsearchCollectionProviderTest extends TestCase
{
    use RecordingElasticsearchTrait;

    /**
     * @param array<string, mixed> $filters
     *
     * @return array<string, mixed>|object the provider's answer, with the request it sent in $this->requests
     */
    private function provide(array $filters, int $total, ?bool $clientEnabled = true, ?LoggerInterface $logger = null): mixed
    {
        $hit = static fn (string $id): array => [
            '_id' => $id,
            '_source' => ['summary' => 'Event '.$id, 'startAt' => '2037-01-01T10:00:00+00:00'],
        ];
        $client = $this->recordingClient(static fn (): array => [200, [
            'hits' => ['total' => ['value' => $total], 'hits' => [
                $hit('01J00000000000000000000001'),
                $hit('01J00000000000000000000002'),
            ]],
        ]]);

        $user = new User();
        $security = $this->createStub(Security::class);
        $security->method('getUser')->willReturn($user);

        $provider = new ElasticsearchCollectionProvider(
            $client,
            new IndexMetadataReader(),
            new ElasticsearchEntityHydrator($this->createStub(EntityManagerInterface::class)),
            new ElasticsearchFilterTranslator(),
            $security,
            $logger ?? $this->createStub(LoggerInterface::class),
            $this->createStub(ProviderInterface::class),
        );

        return $provider->provide(
            new GetCollection(class: Event::class, paginationClientEnabled: $clientEnabled, paginationItemsPerPage: 30),
            [],
            ['filters' => $filters],
        );
    }

    public function testPaginationFalseAsksTheWholeResultWindowAndReturnsAnArray(): void
    {
        $result = $this->provide(['pagination' => 'false', 'page' => '2'], 2);

        self::assertIsArray($result);
        self::assertCount(2, $result);
        $body = $this->lastRequestBody();
        self::assertSame(10000, $body['size']);
        self::assertSame(0, $body['from']);
    }

    public function testPaginationZeroIsTheSameAsFalse(): void
    {
        self::assertIsArray($this->provide(['pagination' => '0'], 2));
        self::assertSame(10000, $this->lastRequestBody()['size']);
    }

    public function testWithoutThePaginationParameterThePageIsServed(): void
    {
        $result = $this->provide(['page' => '2'], 45);

        self::assertInstanceOf(ElasticsearchPaginator::class, $result);
        $body = $this->lastRequestBody();
        self::assertSame(30, $body['size']);
        self::assertSame(30, $body['from']);
    }

    public function testPaginationTrueKeepsThePage(): void
    {
        self::assertInstanceOf(ElasticsearchPaginator::class, $this->provide(['pagination' => 'true'], 45));
    }

    public function testPaginationFalseIsIgnoredWhenTheClientMayNotAskIt(): void
    {
        self::assertInstanceOf(ElasticsearchPaginator::class, $this->provide(['pagination' => 'false'], 45, clientEnabled: false));
        self::assertSame(30, $this->lastRequestBody()['size']);
    }

    public function testAnAnswerCutByTheResultWindowIsLogged(): void
    {
        $logger = $this->createMock(LoggerInterface::class);
        $logger->expects(self::once())->method('warning')->with(
            self::anything(),
            self::callback(static fn (array $context): bool => 'es_collection_truncated' === $context['event']),
        );

        self::assertIsArray($this->provide(['pagination' => 'false'], 10001, logger: $logger));
    }

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
