<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Fixture;

use Symfony\Component\DependencyInjection\Attribute\AutowireIterator;

final class FixtureEffects
{
    /** @param iterable<FixtureEffectsInterface> $modules */
    public function __construct(
        #[AutowireIterator(FixtureEffectsInterface::TAG)]
        private readonly iterable $modules,
    ) {
    }

    /** Runs `$work` with the flush effects of every module suspended. */
    public function withoutEffects(\Closure $work): mixed
    {
        foreach ($this->modules as $module) {
            $work = static fn (): mixed => $module->withoutEffects($work);
        }

        return $work();
    }
}
