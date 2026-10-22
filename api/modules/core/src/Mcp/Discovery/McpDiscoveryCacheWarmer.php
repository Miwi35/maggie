<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Discovery;

use Mcp\Capability\Discovery\DiscovererInterface;
use Psr\SimpleCache\CacheInterface;
use Symfony\Component\HttpKernel\CacheWarmer\CacheWarmerInterface;

/**
 * Builds the MCP tool catalogue at `cache:warmup`, so a request never scans
 * the source tree (~1 100 files, several seconds) to answer `tools/list`.
 *
 * The cache is emptied first: a tool added or changed since the previous
 * warmup must show up, never be masked by a stale entry. The scan arguments
 * are the ones the MCP bundle passes to its builder, so the cache key matches.
 */
final class McpDiscoveryCacheWarmer implements CacheWarmerInterface
{
    /**
     * @param string[] $scanDirs
     * @param string[] $excludeDirs
     */
    public function __construct(
        private readonly DiscovererInterface $discoverer,
        private readonly CacheInterface $cache,
        private readonly string $projectDir,
        private readonly array $scanDirs,
        private readonly array $excludeDirs,
    ) {
    }

    public function isOptional(): bool
    {
        return false;
    }

    public function warmUp(string $cacheDir, ?string $buildDir = null): array
    {
        $this->cache->clear();
        $this->discoverer->discover($this->projectDir, $this->scanDirs, $this->excludeDirs);

        return [];
    }
}
