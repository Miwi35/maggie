<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\Command\ElasticsearchReindexCommand;
use Maggie\Core\Elasticsearch\IndexableEntityRegistry;
use Maggie\Core\Elasticsearch\IndexManager;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Tests\Elasticsearch\RecordingElasticsearchTrait;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * MAG-266: `app:elasticsearch:reindex --orphans` removes the documents whose row
 * is gone — what a deploy's `status --check` flags — without rebuilding anything.
 */
final class ElasticsearchReindexOrphansTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecordingElasticsearchTrait;

    private const string ORPHAN = '01J00000000000000000000ORF';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('RecetteResetCommandTest.yaml');
    }

    public function testItDeletesTheDocumentsWithoutARowAndOnlyThose(): void
    {
        $tester = $this->execute(['--orphans' => true], $this->indexHolding('events', self::ORPHAN));

        $tester->assertCommandIsSuccessful();
        self::assertSame([['delete' => ['_index' => 'events', '_id' => self::ORPHAN]]], $this->bulkOperations());
        self::assertStringContainsString('1 orphan document(s) removed', $tester->getDisplay());
        self::assertSame([], $this->rebuildingRequests(), 'Nothing is created, mapped or indexed.');
    }

    public function testAnIndexInSyncLosesNothing(): void
    {
        $tester = $this->execute(['--orphans' => true], $this->indexHolding('events'));

        $tester->assertCommandIsSuccessful();
        self::assertSame([], $this->bulkOperations());
        self::assertStringContainsString('0 orphan document(s) removed', $tester->getDisplay());
    }

    public function testItCanBeScopedToOneEntity(): void
    {
        $tester = $this->execute(['--orphans' => true, '--entity' => 'Agenda'], $this->indexHolding('events', self::ORPHAN));

        $tester->assertCommandIsSuccessful();
        self::assertSame([], $this->bulkOperations(), 'Only the agendas index is looked at.');
    }

    /**
     * @return \Closure(string, string): array{int, array<string, mixed>}
     */
    private function indexHolding(string $index, string ...$extraIds): \Closure
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $ids = array_map(static fn (Event $event) => (string) $event->getId(), $em->getRepository(Event::class)->findAll());
        $em->clear();

        return static function (string $method, string $path) use ($index, $ids, $extraIds): array {
            if ('POST' === $method && "/{$index}/_search" === $path) {
                return [200, ['_scroll_id' => 's', 'hits' => ['hits' => array_map(static fn (string $id) => ['_id' => $id], [...$ids, ...$extraIds])]]];
            }
            if ('POST' === $method && (str_ends_with($path, '/_search') || '/_search/scroll' === $path)) {
                return [200, ['_scroll_id' => 's', 'hits' => ['hits' => []]]];
            }

            return [200, ['acknowledged' => true, 'errors' => false, 'items' => []]];
        };
    }

    /**
     * @param array<string, mixed>                                       $options
     * @param \Closure(string, string): array{int, array<string, mixed>} $respond
     */
    private function execute(array $options, \Closure $respond): CommandTester
    {
        $reader = new IndexMetadataReader();
        $registry = new IndexableEntityRegistry(self::getContainer()->get(EntityManagerInterface::class), $reader);
        $manager = new IndexManager($this->recordingClient($respond), $reader, $registry, new NullLogger());

        $tester = new CommandTester(new ElasticsearchReindexCommand($registry, $manager, $reader, self::getContainer()->get(EntityManagerInterface::class)));
        $tester->execute($options);

        return $tester;
    }

    /**
     * @return list<array<string, mixed>>
     */
    private function bulkOperations(): array
    {
        $operations = [];
        foreach ($this->requests as $request) {
            if ('/_bulk' === $request['path']) {
                foreach (array_filter(explode("\n", $request['body'])) as $line) {
                    $operations[] = json_decode($line, true, 512, \JSON_THROW_ON_ERROR);
                }
            }
        }

        return $operations;
    }

    /**
     * @return list<string>
     */
    private function rebuildingRequests(): array
    {
        $rebuilding = [];
        foreach ($this->requests as $request) {
            if (\in_array($request['method'], ['PUT'], true) || str_contains($request['path'], '_mapping')) {
                $rebuilding[] = $request['method'].' '.$request['path'];
            }
        }

        return $rebuilding;
    }
}
