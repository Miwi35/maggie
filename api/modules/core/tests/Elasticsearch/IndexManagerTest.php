<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Exception\ClientResponseException;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use PHPUnit\Framework\TestCase;
use Psr\Log\LoggerInterface;

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
            $this->createStub(LoggerInterface::class),
        );
    }

    public function testTheMappingDeclaresTheIdentifierAsAKeyword(): void
    {
        // HEAD /events → 404: the index does not exist yet, so it is created.
        $manager = $this->manager(static fn (string $method): array => 'HEAD' === $method ? [404, []] : [200, ['acknowledged' => true]]);

        $manager->createOrUpdateIndex(Event::class);

        self::assertSame(['type' => 'keyword'], $this->lastRequestBody()['mappings']['properties']['id']);
    }

    /**
     * A new pod can write a document with an `id` before the deploy's mapping
     * update runs; Elasticsearch then maps it as `text` and refuses to make it
     * a keyword. The index is derived data, so it is rebuilt.
     */
    public function testAnIndexWhoseIdWasMappedDynamicallyIsRecreated(): void
    {
        $manager = $this->manager(static fn (string $method, string $path): array => match (true) {
            'HEAD' === $method => [200, []],
            'PUT' === $method && '/events/_mapping' === $path => [400, ['error' => [
                'type' => 'illegal_argument_exception',
                'reason' => 'mapper [id] cannot be changed from type [text] to [keyword]',
            ], 'status' => 400]],
            default => [200, ['acknowledged' => true]],
        });

        $manager->createOrUpdateIndex(Event::class);

        self::assertSame(
            ['HEAD /events', 'PUT /events/_mapping', 'HEAD /events', 'DELETE /events', 'PUT /events'],
            array_map(static fn (array $r): string => $r['method'].' '.$r['path'], $this->requests),
        );
        self::assertSame(['type' => 'keyword'], $this->lastRequestBody()['mappings']['properties']['id']);
    }

    public function testAnotherMappingFailureIsNotSwallowed(): void
    {
        $manager = $this->manager(static fn (string $method): array => 'HEAD' === $method
            ? [200, []]
            : [400, ['error' => ['type' => 'mapper_parsing_exception', 'reason' => 'bad mapping'], 'status' => 400]]);

        $this->expectException(ClientResponseException::class);

        $manager->createOrUpdateIndex(Event::class);
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

    /**
     * The list a user sees is served from the index, and a document is searchable only once the
     * index is refreshed (every second by default): the indices a request wrote are refreshed once.
     */
    public function testEveryWrittenIndexIsRefreshedOnceWhateverTheNumberOfWrites(): void
    {
        $manager = $this->manager(static fn (): array => [200, ['result' => 'created']]);

        $manager->indexDocument('events', '01J0000000000000000000000A', ['summary' => 'Lunch']);
        $manager->deleteDocument('events', '01J0000000000000000000000B');
        $manager->deleteDocument('events', '01J0000000000000000000000C');
        $manager->indexDocument('agendas', '01J0000000000000000000000D', ['name' => 'Perso']);
        $manager->refreshWritten();

        self::assertSame(
            ['PUT /events/_doc/01J0000000000000000000000A', 'DELETE /events/_doc/01J0000000000000000000000B', 'DELETE /events/_doc/01J0000000000000000000000C', 'PUT /agendas/_doc/01J0000000000000000000000D', 'POST /events/_refresh', 'POST /agendas/_refresh'],
            array_map(static fn (array $r): string => $r['method'].' '.$r['path'], $this->requests),
        );
    }

    public function testNothingIsRefreshedWhenNothingWasWritten(): void
    {
        $manager = $this->manager(static fn (): array => [200, []]);

        $manager->refreshWritten();

        self::assertSame([], $this->requests);
    }

    public function testAFailedRefreshDoesNotBreakTheResponse(): void
    {
        $manager = $this->manager(static fn (string $method): array => 'POST' === $method
            ? [503, ['error' => ['type' => 'unavailable', 'reason' => 'down'], 'status' => 503]]
            : [201, ['result' => 'created']]);
        $manager->indexDocument('events', '01J0000000000000000000000A', ['summary' => 'Lunch']);

        $manager->refreshWritten();

        self::assertSame('POST /events/_refresh', $this->requests[1]['method'].' '.$this->requests[1]['path']);
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

    public function testItListsEveryDocumentIdByFollowingTheScroll(): void
    {
        $pages = [
            ['_scroll_id' => 's1', 'hits' => ['hits' => [['_id' => 'A'], ['_id' => 'B']]]],
            ['_scroll_id' => 's2', 'hits' => ['hits' => [['_id' => 'C']]]],
            ['_scroll_id' => 's2', 'hits' => ['hits' => []]],
        ];
        $manager = $this->manager(static function (string $method, string $path) use (&$pages): array {
            return 'POST' === $method && str_ends_with($path, '/_search') || 'POST' === $method && '/_search/scroll' === $path
                ? [200, array_shift($pages)]
                : [200, ['acknowledged' => true]];
        });

        self::assertSame(['A', 'B', 'C'], $manager->documentIds('events'));
        self::assertSame('DELETE /_search/scroll', $this->requests[array_key_last($this->requests)]['method'].' '.$this->requests[array_key_last($this->requests)]['path']);
    }

    public function testAnIndexThatDoesNotExistHoldsNoDocument(): void
    {
        $manager = $this->manager(static fn (): array => [404, ['error' => ['type' => 'index_not_found_exception'], 'status' => 404]]);

        self::assertSame([], $manager->documentIds('events'));
    }
}
