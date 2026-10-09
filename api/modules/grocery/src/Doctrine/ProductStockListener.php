<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Event\ProductOutOfStockEvent;
use Maggie\Grocery\Specification\IsProductOutOfStock;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the events of a product's stock and nothing else: the effects live in their handlers.
 *
 * The changeset only exists during onFlush; the event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class ProductStockListener
{
    /** @var list<array{Product, array<string, array{mixed, mixed}>}> */
    private array $changes = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its changes must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->changes = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates()] as $entity) {
            if ($entity instanceof Product) {
                $changeSet = $uow->getEntityChangeSet($entity);
                if (isset($changeSet['stockState'])) {
                    $this->changes[] = [$entity, $changeSet];
                }
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $changes = $this->changes;
        $this->changes = [];

        foreach ($changes as [$product, $changeSet]) {
            if (!(new IsProductOutOfStock($changeSet))->isSatisfiedBy($product)) {
                continue;
            }

            // The product is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new ProductOutOfStockEvent((string) $product->getId()));
            } catch (\Throwable $e) {
                $this->logger->error('Product {product} ran out but its event failed: {error}', [
                    'product' => (string) $product->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
