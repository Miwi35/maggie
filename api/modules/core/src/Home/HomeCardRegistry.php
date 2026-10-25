<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;

/** The registered home cards, in registration order: the page's order is each front's own layout file. */
final class HomeCardRegistry
{
    public const string TAG = 'maggie.home_card';

    /** @var list<HomeCardProviderInterface> */
    private readonly array $providers;

    /** @param iterable<HomeCardProviderInterface> $providers */
    public function __construct(#[AutowireIterator(self::TAG)] iterable $providers)
    {
        $this->providers = [...$providers];
    }

    /** @return list<HomeCardProviderInterface> */
    public function all(): array
    {
        return $this->providers;
    }

    public function get(string $module): ?HomeCardProviderInterface
    {
        foreach ($this->providers as $provider) {
            if ($provider->module() === $module) {
                return $provider;
            }
        }

        return null;
    }
}
