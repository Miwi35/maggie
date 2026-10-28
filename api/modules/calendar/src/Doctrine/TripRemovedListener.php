<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PostFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Event\TripRemoved;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises TripRemoved when a trip is removed (MAG-679), alone or with its event, whichever door
 * removes it: the alerts about it are not worth saying any more, and the handlers see to that.
 *
 * The removal only shows during onFlush; the event goes out after the flush, never during it.
 *
 * Nothing is reset when a flush starts: another postFlush listener may flush again before this
 * one runs (the replanning an event of today files does), and that nested flush would otherwise
 * make it forget the removal it is about to announce.
 */
#[AsDoctrineListener(event: Events::onFlush)]
// After the projection's WorkCollector has taken the flush's work: its effects flush again, which would make it forget it.
#[AsDoctrineListener(event: Events::postFlush, priority: -10)]
final class TripRemovedListener
{
    /** @var list<string> */
    private array $removed = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        foreach ($args->getObjectManager()->getUnitOfWork()->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Trip) {
                $this->removed[] = (string) $entity->getId();
            }
        }
    }

    public function postFlush(PostFlushEventArgs $args): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $removed = array_unique($this->removed);
        $this->removed = [];

        $em = $args->getObjectManager();
        foreach ($removed as $tripId) {
            // A flush that failed after onFlush left its removals here: a trip still stored was not removed.
            if (null !== $em->find(Trip::class, $tripId)) {
                continue;
            }

            // The trip is already gone: a failing effect must not turn its removal into an error.
            try {
                $this->eventBus->dispatch(new TripRemoved($tripId));
            } catch (\Throwable $e) {
                $this->logger->error('Trip {trip} was removed but its event failed: {error}', [
                    'trip' => $tripId,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
