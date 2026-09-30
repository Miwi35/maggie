<?php

declare(strict_types=1);

namespace Maggie\Cookbook\EventListener;

use Doctrine\ORM\Event\LoadClassMetadataEventArgs;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Grocery\Entity\Product;

class IngredientDiscriminatorListener
{
    public function loadClassMetadata(LoadClassMetadataEventArgs $event): void
    {
        $metadata = $event->getClassMetadata();

        if (Product::class !== $metadata->name) {
            return;
        }

        $discriminatorMap = $metadata->discriminatorMap;
        if (!isset($discriminatorMap['ingredient'])) {
            $metadata->addDiscriminatorMapClass('ingredient', Ingredient::class);
        }
    }
}
