<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Discovery;

use Mcp\Capability\Discovery\CachedDiscoverer;
use Symfony\Component\DependencyInjection\Compiler\CompilerPassInterface;
use Symfony\Component\DependencyInjection\ContainerBuilder;
use Symfony\Component\DependencyInjection\Reference;

/**
 * The MCP bundle builds its server without a discovery cache. Where the cached
 * discoverer is defined (prod only), hand it to the builder; elsewhere the
 * discovery stays dynamic so a new tool shows up without a warmup.
 */
final class CachedMcpDiscoveryPass implements CompilerPassInterface
{
    public function process(ContainerBuilder $container): void
    {
        if (!$container->hasDefinition('mcp.server.builder') || !$container->hasDefinition(CachedDiscoverer::class)) {
            return;
        }

        $container->getDefinition('mcp.server.builder')
            ->addMethodCall('setDiscoverer', [new Reference(CachedDiscoverer::class)]);
    }
}
