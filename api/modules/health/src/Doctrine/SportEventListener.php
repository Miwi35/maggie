<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\E2e\Fixture\FixtureEffectsInterface;
use Maggie\Health\Event\SportEventChanged;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Specification\IsSportEventMoved;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises SportEventChanged when a planned session's event is moved, resized or
 * deleted in the agenda (MAG-777), whichever door saved it — the API, Maggie's
 * `update_event` / `delete_event`, the Agenda view. The effects live in its
 * handler.
 *
 * A deleted event's session is looked up during onFlush: once the row is gone,
 * the database has emptied the session's link. An event deleted with its whole
 * agenda takes no session with it: the user removed the agenda, not their
 * training — the database only unlinks them.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
// Last of all: see DispatchesOnceCommittedTrait.
#[AsDoctrineListener(event: Events::postFlush, priority: -200)]
final class SportEventListener implements FixtureEffectsInterface
{
    /** @use DispatchesOnceCommittedTrait<SportEventChanged> */
    use DispatchesOnceCommittedTrait;

    public function __construct(
        private readonly WorkoutRepository $workoutRepository,
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

        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Event && (new IsSportEventMoved($uow->getEntityChangeSet($entity)))->isSatisfiedBy($entity)) {
                $this->pending[] = new SportEventChanged((string) $entity->getAgenda()->getUser()->getId(), (string) $entity->getId());
            }
        }

        $removedAgendas = [];
        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Agenda) {
                $removedAgendas[(string) $entity->getId()] = true;
            }
        }

        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if (!$entity instanceof Event || Event::ACTIVITY_SPORT !== $entity->getActivity() || isset($removedAgendas[(string) $entity->getAgenda()->getId()])) {
                continue;
            }
            $workoutId = $this->workoutRepository->findIdByEvent($entity->getId());
            if (null !== $workoutId) {
                $this->pending[] = new SportEventChanged((string) $entity->getAgenda()->getUser()->getId(), (string) $entity->getId(), removed: true, workoutId: $workoutId);
            }
        }
    }

    public function postFlush(): void
    {
        foreach ($this->takeReady() as $event) {
            // The event is stored: a failing session must not turn its save into an error.
            try {
                $this->eventBus->dispatch($event);
            } catch (\Throwable $e) {
                $this->logger->error('Sport event {event} changed but its session failed: {error}', [
                    'event' => $event->eventId,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
