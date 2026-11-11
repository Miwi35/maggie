<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\E2e\Fixture\FixtureEffectsInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Event\PlannedWorkoutChanged;
use Maggie\Health\Event\PlannedWorkoutRemoved;
use Maggie\Health\Specification\IsPlannedWorkout;
use Maggie\Health\Specification\IsWorkoutNoLongerPlanned;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the facts that move a session in the « Santé » agenda (MAG-777),
 * whichever door saved it — the API, Maggie, the Health Connect sync, a console
 * command: PlannedWorkoutChanged when a planned session is created or changes
 * what its event shows, PlannedWorkoutRemoved when one with an event is deleted
 * or stops being planned. The effects live in their handlers.
 *
 * The changeset only exists during onFlush; the events go out after the flush,
 * after every other postFlush.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
// Last of all: see DispatchesOnceCommittedTrait.
#[AsDoctrineListener(event: Events::postFlush, priority: -200)]
final class PlannedWorkoutListener implements FixtureEffectsInterface
{
    /** @use DispatchesOnceCommittedTrait<PlannedWorkoutChanged|PlannedWorkoutRemoved> */
    use DispatchesOnceCommittedTrait;

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        if ($this->suspended) {
            return;
        }

        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates()] as $entity) {
            if (!$entity instanceof Workout) {
                continue;
            }
            $changeSet = $uow->getEntityChangeSet($entity);
            $userId = (string) $entity->getUser()->getId();

            if ((new IsPlannedWorkout($changeSet))->isSatisfiedBy($entity)) {
                $this->pending[] = new PlannedWorkoutChanged($userId, (string) $entity->getId());
            } elseif (null !== $entity->getEvent() && (new IsWorkoutNoLongerPlanned($changeSet))->isSatisfiedBy($entity)) {
                $this->pending[] = new PlannedWorkoutRemoved($userId, (string) $entity->getId(), (string) $entity->getEvent()->getId());
            }
        }

        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Workout && null !== $entity->getEvent()) {
                $this->pending[] = new PlannedWorkoutRemoved((string) $entity->getUser()->getId(), (string) $entity->getId(), (string) $entity->getEvent()->getId());
            }
        }
    }

    public function postFlush(): void
    {
        foreach ($this->takeReady() as $event) {
            // The session is stored: a failing agenda must not turn its save into an error.
            try {
                $this->eventBus->dispatch($event);
            } catch (\Throwable $e) {
                $this->logger->error('Planned workout {workout}: {event} failed: {error}', [
                    'workout' => $event->workoutId,
                    'event' => $event::class,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
