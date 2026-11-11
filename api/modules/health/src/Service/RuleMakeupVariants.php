<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Dto\MakeupVariants;
use Maggie\Health\Entity\Workout;

/**
 * The make-ups by rule (MAG-771): « softer » keeps the duration, « shorter »
 * takes half of it rounded down to 5 min, {@see self::MIN_SHORTER_MINUTES} at
 * least, never longer than the session itself.
 */
final class RuleMakeupVariants implements MakeupVariantsProviderInterface
{
    public const MIN_SHORTER_MINUTES = 10;

    public function variantsFor(Workout $missed): MakeupVariants
    {
        $minutes = $missed->getDurationMinutes();
        $half = intdiv(intdiv($minutes, 2), 5) * 5;

        return new MakeupVariants(
            softerMinutes: $minutes,
            shorterMinutes: min($minutes, max(self::MIN_SHORTER_MINUTES, $half)),
        );
    }
}
