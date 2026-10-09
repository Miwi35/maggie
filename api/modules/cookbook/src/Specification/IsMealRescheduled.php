<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Specification;

use Maggie\Cookbook\Entity\Meal;

/** A stored meal's start moved: planning a meal is not moving it, and writing the same instant again is not either. */
final readonly class IsMealRescheduled
{
    /** @param array<string, array{0: mixed, 1: mixed}> $changeSet what Doctrine is about to write: field => [before, after] */
    public function __construct(
        private array $changeSet,
    ) {
    }

    public function isSatisfiedBy(Meal $meal): bool
    {
        if (!isset($this->changeSet['startAt'])) {
            return false;
        }

        [$before, $after] = $this->changeSet['startAt'];

        // Doctrine flags a new DateTime instance as a change even when it is the same instant.
        return null !== $before && $before != $after;
    }
}
