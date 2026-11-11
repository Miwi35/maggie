<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Event\PlannedWorkoutKept;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Specification\CoversPlannedWorkout;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises « séance tenue » (MAG-771) and nothing else: a session done, created
 * or moved, keeps the earliest planned session of its day not kept yet
 * ({@see CoversPlannedWorkout}). Whichever door saved it — the API, MCP, the
 * Health Connect sync.
 *
 * The changeset only exists during onFlush; the event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class PlannedWorkoutKeptListener
{
    /** @var list<array{Workout, array<string, array{mixed, mixed}>}> */
    private array $changes = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly WorkoutRepository $workouts,
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
            if ($entity instanceof Workout) {
                $changeSet = $uow->getEntityChangeSet($entity);
                if ((new CoversPlannedWorkout($changeSet))->isTransition($entity)) {
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

        foreach ($changes as [$done, $changeSet]) {
            // The workout is already stored: a failing effect must not turn its save into an error.
            try {
                [$from, $before] = CoversPlannedWorkout::dayOf($done);
                $planned = (new CoversPlannedWorkout($changeSet))->pick($done, $this->workouts->findKeepablePlanned($done->getUser(), $from, $before));
                if (null !== $planned) {
                    $this->eventBus->dispatch(new PlannedWorkoutKept((string) $done->getId(), (string) $planned->getId()));
                }
            } catch (\Throwable $e) {
                $this->logger->error('Workout {workout} was saved but keeping its planned session failed: {error}', [
                    'workout' => (string) $done->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
