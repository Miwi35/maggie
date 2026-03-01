<?php

namespace Maggie\Core\Mercure\Listener;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Mercure\ChangesetStore;

/**
 * Captures Doctrine UOW changesets before flush clears them.
 *
 * After $em->flush(), the UOW is cleared and changesets are lost.
 * This listener runs during onFlush (before SQL) and stores
 * changed property names so MercurePublishMiddleware can publish
 * differential updates.
 */
#[AsDoctrineListener(event: Events::onFlush)]
class ChangesetCaptureListener
{
    public function __construct(
        private readonly ChangesetStore $changesetStore,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $em = $args->getObjectManager();
        $uow = $em->getUnitOfWork();

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if (!$entity instanceof MercurePublishable) {
                continue;
            }

            $changeSet = $uow->getEntityChangeSet($entity);
            $this->changesetStore->capture($entity, array_keys($changeSet));
        }

        // ManyToMany collection changes (e.g. Meal.recipes)
        foreach ($uow->getScheduledCollectionUpdates() as $collection) {
            $owner = $collection->getOwner();
            if ($owner instanceof MercurePublishable) {
                $mapping = $collection->getMapping();
                $this->changesetStore->capture($owner, [$mapping['fieldName']]);
            }
        }

        foreach ($uow->getScheduledCollectionDeletions() as $collection) {
            $owner = $collection->getOwner();
            if ($owner instanceof MercurePublishable) {
                $mapping = $collection->getMapping();
                $this->changesetStore->capture($owner, [$mapping['fieldName']]);
            }
        }
    }
}
