<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Specification;

use Maggie\Cookbook\Entity\Meal;

/**
 * A stored meal's day moved: planning a meal is not moving it, and writing the same day again is not either.
 *
 * The day is `date`: every door that moves a meal goes through `Meal::setDate()`, and a meal has no instant (MAG-382).
 */
final readonly class IsMealRescheduled
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Meal $meal): bool
    {
        if (!isset($this->changeSet['date'])) {
            return false;
        }

        [$before, $after] = $this->changeSet['date'];

        // Doctrine flags a new DateTime instance as a change even when it is the same day.
        return null !== $before && $before != $after;
    }
}
