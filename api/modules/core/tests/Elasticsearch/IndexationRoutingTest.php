<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Maggie\Calendar\Entity\Event;
use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Transport\InMemory\InMemoryTransport;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Yaml\Yaml;

/**
 * The web client refetches its list as soon as a write returns. The list comes
 * from the index, so the document is indexed inside the request, not by the
 * worker a moment later (the test environment overrides the `async` transport
 * in memory, which is why this reads the production routing).
 */
final class IndexationRoutingTest extends KernelTestCase
{
    /**
     * @return iterable<string, array{class-string}>
     */
    public static function indexationCommands(): iterable
    {
        yield 'index' => [IndexDocumentCommand::class];
        yield 'delete' => [DeleteDocumentCommand::class];
    }

    /**
     * @param class-string $command
     */
    #[DataProvider('indexationCommands')]
    public function testTheDocumentIsIndexedBeforeTheRequestReturns(string $command): void
    {
        $config = Yaml::parseFile(__DIR__.'/../../../../config/packages/messenger.yaml');

        self::assertSame('sync', $config['framework']['messenger']['routing'][$command] ?? null);
    }

    /** The suites assert the dispatch to the in-memory transport: the override must replace the routing, not add to it. */
    public function testTheTestEnvironmentKeepsIndexationAsyncAndInMemory(): void
    {
        self::bootKernel();
        $bus = self::getContainer()->get(MessageBusInterface::class);
        $transport = self::getContainer()->get('messenger.transport.async');
        self::assertInstanceOf(MessageBusInterface::class, $bus);
        self::assertInstanceOf(InMemoryTransport::class, $transport);
        $transport->reset();

        $envelope = $bus->dispatch(new IndexDocumentCommand(Event::class, (string) new Ulid()));

        self::assertCount(1, $transport->getSent());
        self::assertNull($envelope->last(HandledStamp::class), 'The command must not also run in the request.');
    }
}
