<?php

declare(strict_types=1);

namespace Maggie\Core\DomainEvent;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Contract\RecordsDomainEvents;
use Symfony\Contracts\EventDispatcher\EventDispatcherInterface;

/**
 * Remembers which entities changed in a flush; the bus middleware then
 * dispatches what they recorded, once the command that flushed is done.
 *
 * Dispatching from postFlush instead would let a subscriber flush again
 * while the unit of work is still closing the first flush.
 */
#[AsDoctrineListener(event: Events::onFlush)]
class DomainEventCollector
{
    /** @var array<int, RecordsDomainEvents> */
    private array $changed = [];

    public function __construct(
        private readonly EventDispatcherInterface $dispatcher,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        // A new row has no previous state to leave: what it recorded while being built is not a transition.
        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof RecordsDomainEvents) {
                $entity->releaseDomainEvents();
            }
        }

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof RecordsDomainEvents) {
                $this->changed[spl_object_id($entity)] = $entity;
            }
        }
    }

    public function dispatchPending(): void
    {
        while ([] !== $this->changed) {
            $entities = $this->changed;
            $this->changed = [];

            foreach ($entities as $entity) {
                foreach ($entity->releaseDomainEvents() as $event) {
                    $this->dispatcher->dispatch($event);
                }
            }
        }
    }
}
