<?php

declare(strict_types=1);

namespace Maggie\Finance\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Finance\Entity\FinanceDashboardSnapshot;
use Maggie\Finance\Event\FinanceFiguresChanged;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises « the finances of this user changed » for every flush that stores,
 * changes or removes one of their finance rows (MAG-545), whichever door it
 * came through — the bus, an import, a console command. The dashboard read
 * model is rebuilt from that event.
 *
 * One event per owner and per flush, after the flush: the rows are stored when
 * the handler runs. A user removed in the same flush is skipped, there is no
 * dashboard left to invalidate.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush, priority: -100)]
final class FinanceFiguresListener
{
    private const ENTITY_NAMESPACE = 'Maggie\Finance\Entity\\';

    /** @var array<string, true> the owners whose figures this flush touched, by user id */
    private array $owners = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: what it noted must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->owners = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();
        $removed = [];

        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof User) {
                $removed[(string) $entity->getId()] = true;
            }
        }

        foreach ([
            ...$uow->getScheduledEntityInsertions(),
            ...$uow->getScheduledEntityUpdates(),
            ...$uow->getScheduledEntityDeletions(),
        ] as $entity) {
            if (!str_starts_with($entity::class, self::ENTITY_NAMESPACE) || $entity instanceof FinanceDashboardSnapshot) {
                continue;
            }

            $ownerId = WorkCollector::ownerOf($entity);
            if (null !== $ownerId && !isset($removed[$ownerId])) {
                $this->owners[$ownerId] = true;
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $owners = array_keys($this->owners);
        $this->owners = [];

        foreach ($owners as $ownerId) {
            // The rows are stored: a failing effect must not turn their save into an error.
            try {
                $this->eventBus->dispatch(new FinanceFiguresChanged((string) $ownerId));
            } catch (\Throwable $e) {
                $this->logger->error('Finance dashboard of {user}: FinanceFiguresChanged failed: {error}', [
                    'user' => $ownerId,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
