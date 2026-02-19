<?php

declare(strict_types=1);

namespace Maggie\Cookbook\EventListener;

use Doctrine\ORM\Event\LoadClassMetadataEventArgs;
use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Entity\Meal;

class MealDiscriminatorListener
{
    public function loadClassMetadata(LoadClassMetadataEventArgs $event): void
    {
        $metadata = $event->getClassMetadata();

        if ($metadata->name !== Event::class) {
            return;
        }

        $discriminatorMap = $metadata->discriminatorMap;
        if (!isset($discriminatorMap['meal'])) {
            $metadata->addDiscriminatorMapClass('meal', Meal::class);
        }
    }
}
