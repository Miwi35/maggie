<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\PrePersistEventArgs;
use Doctrine\ORM\Events;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Service\SeasonalityCatalog;

/**
 * Pre-fills a new product's season months from the seasonality calendar
 * (MAG-494), whichever way it is created — the API, a tool, a free-text
 * grocery line, an ingredient from Ciqual. Months the creator gave win.
 */
#[AsDoctrineListener(event: Events::prePersist)]
final class ProductSeasonListener
{
    public function __construct(
        private readonly SeasonalityCatalog $catalog,
    ) {
    }

    public function prePersist(PrePersistEventArgs $args): void
    {
        $product = $args->getObject();

        if ($product instanceof Product && null === $product->getSeasonMonths()) {
            $product->setSeasonMonths($this->catalog->monthsForProduct($product));
        }
    }
}
