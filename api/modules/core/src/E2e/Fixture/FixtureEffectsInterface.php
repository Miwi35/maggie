<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Fixture;

use Symfony\Component\DependencyInjection\Attribute\AutoconfigureTag;

/**
 * A module whose flush listeners have effects that fixtures, being history,
 * must not trigger (a movement recorded, a categorisation run). The seed and
 * the journey users load their fixtures through {@see FixtureEffects}, which
 * suspends every module that declares itself here.
 */
#[AutoconfigureTag(FixtureEffectsInterface::TAG)]
interface FixtureEffectsInterface
{
    public const TAG = 'maggie.fixture_effects';

    /** Runs `$work` with this module's flush effects suspended. */
    public function withoutEffects(\Closure $work): mixed;
}
