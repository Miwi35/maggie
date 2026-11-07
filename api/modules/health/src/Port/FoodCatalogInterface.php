<?php

declare(strict_types=1);

namespace Maggie\Health\Port;

/** The Ciqual foods (MAG-748), served by the cookbook's Ciqual client. */
interface FoodCatalogInterface
{
    /**
     * The food of this Ciqual code, or null when the code is unknown.
     *
     * @throws \RuntimeException when Ciqual cannot be reached
     */
    public function food(string $code): ?CiqualFood;
}
