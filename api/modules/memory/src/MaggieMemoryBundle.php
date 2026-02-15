<?php

declare(strict_types=1);

namespace Maggie\Memory;

use Symfony\Component\DependencyInjection\ContainerBuilder;
use Symfony\Component\DependencyInjection\Loader\Configurator\ContainerConfigurator;
use Symfony\Component\HttpKernel\Bundle\AbstractBundle;

final class MaggieMemoryBundle extends AbstractBundle
{
    public function getPath(): string
    {
        return \dirname(__DIR__);
    }

    public function prependExtension(ContainerConfigurator $container, ContainerBuilder $builder): void
    {
        if ($builder->hasExtension('doctrine')) {
            $builder->prependExtensionConfig('doctrine', [
                'orm' => [
                    'mappings' => [
                        'MaggieMemory' => [
                            'type' => 'attribute',
                            'is_bundle' => false,
                            'dir' => $this->getPath() . '/src/Entity',
                            'prefix' => 'Maggie\Memory\Entity',
                            'alias' => 'MaggieMemory',
                        ],
                    ],
                ],
            ]);
        }
    }

    /** @param array<mixed> $config */
    public function loadExtension(array $config, ContainerConfigurator $container, ContainerBuilder $builder): void
    {
        $container->import('../config/services.yaml');
    }
}
