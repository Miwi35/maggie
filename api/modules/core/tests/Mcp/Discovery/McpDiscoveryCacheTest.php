<?php

namespace Maggie\Core\Tests\Mcp\Discovery;

use Maggie\Core\Mcp\Discovery\CachedMcpDiscoveryPass;
use Maggie\Core\Mcp\Discovery\McpDiscoveryCacheWarmer;
use Mcp\Capability\Discovery\CachedDiscoverer;
use Mcp\Capability\Discovery\Discoverer;
use Mcp\Capability\Discovery\DiscovererInterface;
use Mcp\Capability\Discovery\DiscoveryState;
use Mcp\Capability\Registry\ToolReference;
use Mcp\Schema\Tool;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Cache\Psr16Cache;
use Symfony\Component\DependencyInjection\ContainerBuilder;
use Symfony\Component\DependencyInjection\Definition;
use Symfony\Component\DependencyInjection\Reference;

class McpDiscoveryCacheTest extends TestCase
{
    private const PROJECT_DIR = __DIR__.'/../../../../../';
    private const SCAN_DIRS = ['modules/core/src'];

    private Psr16Cache $cache;

    protected function setUp(): void
    {
        $this->cache = new Psr16Cache(new ArrayAdapter());
    }

    public function testCachedCatalogueKeepsEveryRealToolAfterTheSerializationRoundTrip(): void
    {
        $inner = new Discoverer(new NullLogger());
        $fresh = $inner->discover(self::PROJECT_DIR, self::SCAN_DIRS);
        $cached = new CachedDiscoverer($inner, $this->cache, new NullLogger());

        $cached->discover(self::PROJECT_DIR, self::SCAN_DIRS);
        $fromCache = $cached->discover(self::PROJECT_DIR, self::SCAN_DIRS);

        self::assertArrayHasKey('get_user_timezone', $fresh->getTools());
        self::assertEquals($fresh, $fromCache);
    }

    public function testWarmupServesTheCatalogueWithoutScanningAgain(): void
    {
        $inner = $this->countingDiscoverer(['old_tool']);
        $cached = new CachedDiscoverer($inner, $this->cache, new NullLogger());
        $this->warmer($inner, $cached)->warmUp('/unused');
        self::assertSame(1, $inner->calls);

        $first = $cached->discover('/project', ['src'], ['DataFixtures']);
        $second = $cached->discover('/project', ['src'], ['DataFixtures']);

        self::assertSame(1, $inner->calls, 'requests must not trigger a scan once warmed');
        self::assertSame(['old_tool'], array_keys($first->getTools()));
        self::assertEquals($first, $second);
    }

    public function testANewToolAppearsAfterTheNextWarmup(): void
    {
        $inner = $this->countingDiscoverer(['old_tool']);
        $cached = new CachedDiscoverer($inner, $this->cache, new NullLogger());
        $warmer = $this->warmer($inner, $cached);
        $warmer->warmUp('/unused');

        $inner->tools = ['old_tool', 'new_tool'];
        self::assertSame(['old_tool'], array_keys($cached->discover('/project', ['src'], ['DataFixtures'])->getTools()));

        $warmer->warmUp('/unused');

        self::assertSame(['old_tool', 'new_tool'], array_keys($cached->discover('/project', ['src'], ['DataFixtures'])->getTools()));
    }

    public function testThePassHandsTheCachedDiscovererToTheBuilderWhenItIsDefined(): void
    {
        $container = new ContainerBuilder();
        $container->setDefinition('mcp.server.builder', new Definition(\stdClass::class));
        $container->setDefinition(CachedDiscoverer::class, new Definition(CachedDiscoverer::class));

        (new CachedMcpDiscoveryPass())->process($container);

        $calls = $container->getDefinition('mcp.server.builder')->getMethodCalls();
        self::assertCount(1, $calls);
        self::assertSame('setDiscoverer', $calls[0][0]);
        self::assertEquals(new Reference(CachedDiscoverer::class), $calls[0][1][0]);
    }

    public function testThePassLeavesTheDiscoveryDynamicWhereNoCacheIsDefined(): void
    {
        $container = new ContainerBuilder();
        $container->setDefinition('mcp.server.builder', new Definition(\stdClass::class));

        (new CachedMcpDiscoveryPass())->process($container);

        self::assertSame([], $container->getDefinition('mcp.server.builder')->getMethodCalls());
    }

    private function warmer(DiscovererInterface $inner, DiscovererInterface $cached): McpDiscoveryCacheWarmer
    {
        return new McpDiscoveryCacheWarmer($cached, $this->cache, '/project', ['src'], ['DataFixtures']);
    }

    /** @param string[] $tools */
    private function countingDiscoverer(array $tools): object
    {
        return new class($tools) implements DiscovererInterface {
            public int $calls = 0;

            /** @param string[] $tools */
            public function __construct(public array $tools)
            {
            }

            public function discover(string $basePath, array $directories, array $excludeDirs = []): DiscoveryState
            {
                ++$this->calls;
                $references = [];
                foreach ($this->tools as $name) {
                    $references[$name] = new ToolReference(new Tool($name, ['type' => 'object'], null, null), 'Handler::run');
                }

                return new DiscoveryState($references);
            }
        };
    }
}
