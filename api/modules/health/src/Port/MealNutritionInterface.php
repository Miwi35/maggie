<?php

declare(strict_types=1);

namespace Maggie\Health\Port;

use Maggie\Core\Entity\User;

/**
 * The planned meals as Santé reads them (MAG-748). Health depends on Core
 * only: the cookbook implements this port, declared here, the way the modules
 * implement Core's (`agent-os/standards/backend/module-boundaries.md`).
 */
interface MealNutritionInterface
{
    /**
     * The user's meals of a day, lunch before dinner.
     *
     * @return list<MealPortion>
     */
    public function plannedOn(User $user, \DateTimeImmutable $day): array;

    /** The user's meal, or null — another user's meal is not found. */
    public function ofMeal(User $user, string $mealId): ?MealPortion;
}
