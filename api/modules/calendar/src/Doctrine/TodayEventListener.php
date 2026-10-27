<?php

declare(strict_types=1);

namespace Maggie\Calendar\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Event\EventTripsChangedEvent;
use Maggie\Calendar\Specification\HasTodayEventChanged;
use Psr\Clock\ClockInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises EventTripsChangedEvent when an event of today is created, moved, relocated or removed
 * (MAG-656), whichever door saves it, and nothing else: the effects live in its handlers.
 *
 * The changeset only exists during onFlush; the event goes out after the flush, never during it.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class TodayEventListener
{
    /** @var list<array{Event, HasTodayEventChanged, EventTripsChangedEvent::*}> */
    private array $changes = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly ClockInterface $clock,
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
        $now = $this->clock->now();

        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof Event) {
                $this->changes[] = [$entity, HasTodayEventChanged::created($now), EventTripsChangedEvent::CREATED];
            }
        }
        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Event) {
                $this->changes[] = [$entity, HasTodayEventChanged::updated($uow->getEntityChangeSet($entity), $now), EventTripsChangedEvent::UPDATED];
            }
        }
        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Event) {
                $this->changes[] = [$entity, HasTodayEventChanged::removed($now), EventTripsChangedEvent::DELETED];
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again (filing the proaction does): forget what is being dispatched before it does.
        $changes = $this->changes;
        $this->changes = [];

        foreach ($changes as [$event, $specification, $change]) {
            if (!$specification->isSatisfiedBy($event)) {
                continue;
            }

            // The event is already stored: a failing effect must not turn its save into an error.
            try {
                $this->eventBus->dispatch(new EventTripsChangedEvent((string) $event->getId(), (string) $event->getAgenda()->getUser()->getId(), $change));
            } catch (\Throwable $e) {
                $this->logger->error('Event {event} changed the trips of today but its event failed: {error}', [
                    'event' => (string) $event->getId(),
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }
}
