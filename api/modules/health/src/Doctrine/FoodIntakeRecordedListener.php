<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Event\FoodIntakeRecorded;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises FoodIntakeRecorded when an intake is inserted, whichever door saved
 * it (MAG-748); the health journal (MAG-750) reads it. The insertion is the
 * only transition, so there is no specification to ask. Noted during onFlush,
 * sent after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class FoodIntakeRecordedListener
{
    /** @var list<FoodIntake> */
    private array $recorded = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its insertions must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->recorded = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        foreach ($args->getObjectManager()->getUnitOfWork()->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof FoodIntake) {
                $this->recorded[] = $entity;
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $recorded = $this->recorded;
        $this->recorded = [];

        foreach ($recorded as $intake) {
            // The intake is already stored: a failing reader must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new FoodIntakeRecorded(
                    (string) $intake->getId(),
                    (string) $intake->getUser()->getId(),
                    (string) $intake->getDate()?->format('Y-m-d'),
                ));
            } catch (\Throwable $e) {
                $this->logger->error('Food intake {intake} was recorded but its event failed: {error}', [
                    'intake' => (string) $intake->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
