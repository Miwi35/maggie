<?php

declare(strict_types=1);

namespace Maggie\Grocery\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Event\ErrandEnded;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises ErrandEnded for each errand stored (MAG-765): an errand is written once, when it ends, so its insertion is
 * the transition. The event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class ErrandEndedListener
{
    /** @var list<Errand> */
    private array $inserted = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its errands must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->inserted = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        foreach ($args->getObjectManager()->getUnitOfWork()->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof Errand) {
                $this->inserted[] = $entity;
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $inserted = $this->inserted;
        $this->inserted = [];

        foreach ($inserted as $errand) {
            // The errand is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new ErrandEnded(
                    errandId: (string) $errand->getId(),
                    userId: (string) $errand->getUser()->getId(),
                    storeId: null !== $errand->getStore() ? (string) $errand->getStore()->getId() : null,
                    endedAt: $errand->getEndedAt(),
                    paidAmountCents: $errand->getPaidAmountCents(),
                ));
            } catch (\Throwable $e) {
                $this->logger->error('Errand {errand} ended but its event failed: {error}', [
                    'errand' => (string) $errand->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
