<?php

declare(strict_types=1);

namespace Maggie\Core\Home;

use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;

/** The registered home cards, in the order of the page: the action cards first. */
final class HomeCardRegistry
{
    public const string TAG = 'maggie.home_card';

    public const array ORDER = ['tasks', 'agenda', 'grocery', 'nutrition', 'health', 'finance', 'leisure'];

    /** @var list<HomeCardProviderInterface> */
    private readonly array $providers;

    /** @param iterable<HomeCardProviderInterface> $providers */
    public function __construct(#[AutowireIterator(self::TAG)] iterable $providers)
    {
        $providers = [...$providers];
        // Stable: a module outside ORDER goes last, in registration order.
        usort($providers, static fn (HomeCardProviderInterface $a, HomeCardProviderInterface $b): int => self::rank($a) <=> self::rank($b));
        $this->providers = $providers;
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

    private static function rank(HomeCardProviderInterface $provider): int
    {
        $rank = array_search($provider->module(), self::ORDER, true);

        return false === $rank ? \count(self::ORDER) : $rank;
    }
}
