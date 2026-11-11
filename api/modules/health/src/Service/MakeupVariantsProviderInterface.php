<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Dto\MakeupVariants;
use Maggie\Health\Entity\Workout;

/**
 * What a missed session's make-ups last (MAG-771). {@see RuleMakeupVariants}
 * by default; the coach (part 14d) brings its own.
 *
 * Not in `Port/`: that layer is what other modules see of Health and may only
 * depend on Core (`api/deptrac.yaml`), while this one takes a Workout.
 */
interface MakeupVariantsProviderInterface
{
    public function variantsFor(Workout $missed): MakeupVariants;
}
