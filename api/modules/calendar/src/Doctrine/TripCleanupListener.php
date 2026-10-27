<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\PreRemoveEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;

/**
 * An event removed takes its trip with it (MAG-654), whichever door removes it.
 *
 * The database would cascade on its own, but silently: removed in the same
 * unit of work, the trip is seen by the projection and its screens are told
 * `{"deleted": true}`.
 */
#[AsDoctrineListener(event: Events::preRemove)]
final class TripCleanupListener
{
    public function preRemove(PreRemoveEventArgs $args): void
    {
        $event = $args->getObject();
        if (!$event instanceof Event) {
            return;
        }

        $em = $args->getObjectManager();
        $trip = $em->getRepository(Trip::class)->findOneBy(['event' => $event]);
        if (null !== $trip) {
            $em->remove($trip);
        }
    }
}
