<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;

/**
 * A document's `_id` is not a field, and Elasticsearch 8 cannot sort on it.
 * `order[id]` — react-admin's default — needs an `id` keyword in the mapping
 * and in every document, whichever path indexed it.
 */
final class IndexManagerTest extends TestCase
{
    use RecordingElasticsearchTrait;

    private function manager(\Closure $respond): IndexManager
    {
        $reader = new IndexMetadataReader();

        return new IndexManager(
            $this->recordingClient($respond),
            $reader,
            new IndexableEntityRegistry($this->createStub(EntityManagerInterface::class), $reader),
        );
    }

    public function testTheMappingDeclaresTheIdentifierAsAKeyword(): void
    {
        // HEAD /events → 404: the index does not exist yet, so it is created.
        $manager = $this->manager(static fn (string $method): array => 'HEAD' === $method ? [404, []] : [200, ['acknowledged' => true]]);

        $manager->createOrUpdateIndex(Event::class);

        self::assertSame(['type' => 'keyword'], $this->lastRequestBody()['mappings']['properties']['id']);
    }

    public function testASingleDocumentCarriesItsIdentifier(): void
    {
        $manager = $this->manager(static fn (): array => [201, ['result' => 'created']]);

        $manager->indexDocument('events', '01J0000000000000000000000A', ['summary' => 'Lunch']);

        self::assertSame(
            ['summary' => 'Lunch', 'id' => '01J0000000000000000000000A'],
            $this->lastRequestBody(),
        );
    }

    public function testABulkDocumentCarriesItsIdentifier(): void
    {
        $manager = $this->manager(static fn (): array => [200, ['errors' => false, 'items' => []]]);

        $manager->bulkIndex([
            ['index' => 'events', 'id' => '01J0000000000000000000000A', 'document' => ['summary' => 'Lunch']],
        ]);

        $lines = array_map(
            static fn (string $line): mixed => json_decode($line, true, 512, \JSON_THROW_ON_ERROR),
            array_values(array_filter(explode("\n", $this->requests[0]['body']))),
        );

        self::assertSame(
            [
                ['index' => ['_index' => 'events', '_id' => '01J0000000000000000000000A']],
                ['summary' => 'Lunch', 'id' => '01J0000000000000000000000A'],
            ],
            $lines,
        );
    }
}
