<?php

declare(strict_types=1);

namespace Maggie\Grocery\Service;

use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Grocery\Entity\GroceryList;

/**
 * Publishes a grocery list a handler changed but does not return.
 *
 * A meal handler returns the meal, so the list it just filled or emptied was
 * neither pushed to the open screens nor reindexed (MAG-116). The rule lives
 * in {@see EntityBroadcaster}; this only accepts the null a sync may give.
 */
class GroceryListBroadcaster
{
    public function __construct(
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    public function broadcast(?GroceryList $list): void
    {
        if (null === $list) {
            return;
        }

        $this->broadcaster->broadcast($list);
    }
}
