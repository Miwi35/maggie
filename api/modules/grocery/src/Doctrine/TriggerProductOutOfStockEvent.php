<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\DomainEvent\PendingDomainEvents;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Maggie\Grocery\Specification\IsProductOutOfStock;

/** Triggers the event and nothing else: the effects live in the subscribers. */
#[AsDoctrineListener(event: Events::onFlush)]
final class TriggerProductOutOfStockEvent
{
    public function __construct(
        private readonly PendingDomainEvents $pending,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates()] as $entity) {
            if ($entity instanceof Product && (new IsProductOutOfStock($uow->getEntityChangeSet($entity)))->isSatisfiedBy($entity)) {
                $this->pending->defer(new ProductOutOfStockEvent($entity));
            }
        }
    }
}
