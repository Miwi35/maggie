<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use ApiPlatform\Metadata\Get;
use ApiPlatform\State\ProviderInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Grocery\Entity\Store;
use PHPUnit\Framework\MockObject\MockObject;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;
use Symfony\Bundle\SecurityBundle\Security;

/**
 * An IRI sent in a write body is resolved through this provider (`fetch_data`):
 * it must give the managed entity, never a copy rebuilt from the index, which
 * Doctrine would take for a new row (MAG-603).
 */
final class ElasticsearchItemProviderRelationTest extends TestCase
{
    use RecordingElasticsearchTrait;

    private ProviderInterface&MockObject $doctrine;

    private function provider(): ElasticsearchItemProvider
    {
        $this->doctrine = $this->createMock(ProviderInterface::class);

        return new ElasticsearchItemProvider(
            $this->recordingClient(static fn (): array => [500, ['error' => 'the index must not be asked']]),
            new IndexMetadataReader(),
            new ElasticsearchEntityHydrator($this->createStub(EntityManagerInterface::class)),
            $this->createStub(Security::class),
            $this->createStub(LoggerInterface::class),
            $this->doctrine,
        );
    }

    public function testAnIriResolvedToBeAttachedComesFromDoctrineWithoutAskingTheIndex(): void
    {
        $provider = $this->provider();
        $store = new Store();
        $operation = new Get(class: Store::class);
        $context = ['fetch_data' => true];
        $this->doctrine->expects(self::once())->method('provide')
            ->with($operation, ['id' => 'abc'], $context)
            ->willReturn($store);

        self::assertSame($store, $provider->provide($operation, ['id' => 'abc'], $context));
        self::assertSame([], $this->requests);
    }

    public function testAReadOfTheItemStillGoesThroughTheIndex(): void
    {
        $provider = $this->provider();
        $this->doctrine->expects(self::once())->method('provide')->willReturn(null);

        try {
            $provider->provide(new Get(class: Store::class), ['id' => '01M5PZ7C04JQ39RZM9ZDE84SVH'], []);
        } catch (\Throwable) {
        }

        self::assertNotSame([], $this->requests);
    }
}
