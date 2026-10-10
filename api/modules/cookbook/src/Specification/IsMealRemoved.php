<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Specification;

use Maggie\Cookbook\Entity\Meal;

/** The meal is among the entities the flush is about to delete. */
final readonly class IsMealRemoved
{
    /** @param list<object> $scheduledDeletions what Doctrine is about to delete */
    public function __construct(
        private array $scheduledDeletions,
    ) {
    }

    public function isSatisfiedBy(object $entity): bool
    {
        return $entity instanceof Meal && \in_array($entity, $this->scheduledDeletions, true);
    }
}
