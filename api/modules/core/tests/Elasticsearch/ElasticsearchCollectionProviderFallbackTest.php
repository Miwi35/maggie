<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\State\ProviderInterface;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Exception\ClientResponseException;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\Query\ElasticsearchFilterTranslator;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;
use Symfony\Bundle\SecurityBundle\Security;

/**
 * Collections of indexed entities come from Elasticsearch and fall back to
 * Doctrine on any exception. The two do not answer with the same fields, so a
 * fallback must never pass unnoticed: it is logged as an error, with the
 * entity and the cause.
 */
final class ElasticsearchCollectionProviderFallbackTest extends TestCase
{
    use RecordingElasticsearchTrait;

    private LoggerInterface&MockObject $logger;
    private ProviderInterface&MockObject $doctrine;

    private function provider(\Closure $respond): ElasticsearchCollectionProvider
    {
        $this->logger = $this->createMock(LoggerInterface::class);
        $this->doctrine = $this->createMock(ProviderInterface::class);

        $security = $this->createStub(Security::class);
        $security->method('getUser')->willReturn(new User());

        return new ElasticsearchCollectionProvider(
            $this->recordingClient($respond),
            new IndexMetadataReader(),
            new ElasticsearchEntityHydrator($this->createStub(EntityManagerInterface::class)),
            new ElasticsearchFilterTranslator(),
            $security,
            $this->logger,
            $this->doctrine,
            'prod',
        );
    }

    public function testAFailedSearchFallsBackToDoctrineAndIsLoggedAsAnError(): void
    {
        $provider = $this->provider(static fn (): array => [400, [
            'error' => [
                'type' => 'search_phase_execution_exception',
                'reason' => 'No mapping found for [id] in order to sort on',
            ],
            'status' => 400,
        ]]);

        $this->logger->expects(self::never())->method('warning');
        $this->logger->expects(self::once())->method('error')->with(
            self::stringContains('falling back to Doctrine'),
            self::callback(static function (array $context): bool {
                return 'es_collection_fallback' === $context['event']
                    && Event::class === $context['entity']
                    && 'events' === $context['index']
                    && str_contains($context['error'], 'No mapping found for [id]')
                    && ClientResponseException::class === $context['exception']
                    && ['order' => ['id' => 'asc']] === $context['filters'];
            }),
        );

        $expected = ['from doctrine'];
        $this->doctrine->expects(self::once())->method('provide')->willReturn($expected);

        self::assertSame($expected, $provider->provide(
            new GetCollection(class: Event::class),
            [],
            ['filters' => ['order' => ['id' => 'asc']]],
        ));
    }

    public function testASortOnTheIdentifierIsSentToElasticsearchOnTheIndexedIdField(): void
    {
        $provider = $this->provider(static fn (): array => [200, [
            'hits' => ['total' => ['value' => 0, 'relation' => 'eq'], 'hits' => []],
        ]]);

        $this->logger->expects(self::never())->method('error');
        $this->doctrine->expects(self::never())->method('provide');

        $provider->provide(
            new GetCollection(class: Event::class),
            [],
            ['filters' => ['order' => ['id' => 'ASC']]],
        );

        self::assertSame([['id' => 'asc']], $this->lastRequestBody()['sort']);
    }
}
