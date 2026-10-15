<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

/**
 * A line that shows whether its product is in season (MAG-494): a grocery
 * item, a recipe's ingredient. Its `inSeason` is the product's.
 */
interface SeasonalLine
{
    /** Not a getter on purpose: the line's product is serialised on its own. */
    public function seasonalProduct(): ?Product;
}
