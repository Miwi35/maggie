<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Event\ProactionInterrupted;
use Maggie\Calendar\Specification\IsProactionInterrupting;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises {@see ProactionInterrupted} when a run completes with an interruption
 * (MAG-886), whichever door records it: the notification follows the
 * transition, not the agent. The changeset only exists during onFlush; the
 * event goes out after the flush.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush, priority: -100)]
final class ProactionDeliveryListener
{
    /** @var list<array{Proaction, array<string, array{0: mixed, 1: mixed}>}> */
    private array $pending = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its events must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->pending = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        // Updates only: a proaction is created pending, so its creation is never a run completing.
        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Proaction) {
                $this->pending[] = [$entity, $uow->getEntityChangeSet($entity)];
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $pending = $this->pending;
        $this->pending = [];

        foreach ($pending as [$proaction, $changeSet]) {
            if (!(new IsProactionInterrupting($changeSet))->isSatisfiedBy($proaction)) {
                continue;
            }

            // The proaction is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new ProactionInterrupted((string) $proaction->getId()));
            } catch (\Throwable $e) {
                $this->logger->error('Proaction {proaction} interrupted but its event failed: {error}', [
                    'proaction' => (string) $proaction->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
