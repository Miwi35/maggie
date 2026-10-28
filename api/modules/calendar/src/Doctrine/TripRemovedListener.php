<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
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
 */
#[AsDoctrineListener(event: Events::preFlush)]
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

    /** A flush that failed after onFlush never reached postFlush: its removals must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->removed = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        foreach ($args->getObjectManager()->getUnitOfWork()->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Trip) {
                $this->removed[] = (string) $entity->getId();
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $removed = $this->removed;
        $this->removed = [];

        foreach ($removed as $tripId) {
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
