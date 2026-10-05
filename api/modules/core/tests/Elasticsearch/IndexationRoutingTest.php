<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Elasticsearch;

use Maggie\Core\Elasticsearch\Message\DeleteDocumentCommand;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Yaml\Yaml;

/**
 * The web client refetches its list as soon as a write returns. The list comes
 * from the index, so the document is indexed inside the request, not by the
 * worker a moment later (the test environment overrides the `async` transport
 * in memory, which is why this reads the production routing).
 */
final class IndexationRoutingTest extends TestCase
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
}
