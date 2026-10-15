<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\MealGuests;

/**
 * Hands every meal about to be written its owner's default number of guests
 * (MAG-482), so the Mercure update the flush announces carries the number that
 * counts — the API's — rather than the built-in fallback.
 */
#[AsDoctrineListener(event: Events::onFlush)]
final class MealGuestsListener
{
    public function __construct(
        private readonly MealGuests $mealGuests,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        // A recipe swap changes only the collection, and still announces the meal.
        $owners = array_map(static fn ($collection) => $collection->getOwner(), $uow->getScheduledCollectionUpdates());

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates(), ...$owners] as $entity) {
            if ($entity instanceof Meal) {
                $this->mealGuests->resolve($entity);
            }
        }
    }
}
