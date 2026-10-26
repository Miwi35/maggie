<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\PrePersistEventArgs;
use Doctrine\ORM\Events;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Service\PieceWeightCatalog;

/**
 * Pre-fills a new product's weight per piece from the average-weights table
 * (MAG-647), whichever way it is created. A weight the creator gave wins.
 */
#[AsDoctrineListener(event: Events::prePersist)]
final class ProductPieceWeightListener
{
    public function __construct(
        private readonly PieceWeightCatalog $catalog,
    ) {
    }

    public function prePersist(PrePersistEventArgs $args): void
    {
        $product = $args->getObject();

        if ($product instanceof Product && null === $product->getPieceWeightGrams()) {
            $product->setPieceWeightGrams($this->catalog->gramsForProduct($product));
        }
    }
}
