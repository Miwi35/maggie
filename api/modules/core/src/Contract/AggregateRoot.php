<?php

declare(strict_types=1);

namespace Maggie\Core\Contract;

/**
 * Marks an entity that is only ever shown inside another: a grocery line is a
 * row of the grocery list, a recipe's ingredient a row of the recipe.
 *
 * The screens subscribe to the root, so a change to the child is published and
 * reindexed as a change to the root: `#[AggregateRoot('groceryList')]` names the
 * property that holds it.
 */
#[\Attribute(\Attribute::TARGET_CLASS)]
final readonly class AggregateRoot
{
    public function __construct(
        public string $relation,
    ) {
    }
}
