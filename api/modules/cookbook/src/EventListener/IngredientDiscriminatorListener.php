<?php

declare(strict_types=1);

namespace Maggie\Cookbook\EventListener;

use Doctrine\ORM\Event\LoadClassMetadataEventArgs;
use Maggie\Grocery\Entity\Product;
use Maggie\Cookbook\Entity\Ingredient;

class IngredientDiscriminatorListener
{
    public function loadClassMetadata(LoadClassMetadataEventArgs $event): void
    {
        $metadata = $event->getClassMetadata();

        if ($metadata->name !== Product::class) {
            return;
        }

        $discriminatorMap = $metadata->discriminatorMap;
        if (!isset($discriminatorMap['ingredient'])) {
            $metadata->addDiscriminatorMapClass('ingredient', Ingredient::class);
        }
    }
}
