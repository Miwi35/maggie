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
use Maggie\Core\E2e\Fixture\FixtureEffectsInterface;
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
// After the projection's WorkCollector has taken the flush's work: its effects flush again, which would make it forget it.
#[AsDoctrineListener(event: Events::postFlush, priority: -10)]
final class TodayEventListener implements FixtureEffectsInterface
{
    /** @var list<array{Event, HasTodayEventChanged, EventTripsChangedEvent::*}> */
    private array $changes = [];

    private bool $suspended = false;

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly ClockInterface $clock,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** Runs work whose flushes store history as it is — fixtures, the e2e seed — and raise no event. */
    public function withoutEffects(\Closure $work): mixed
    {
        $this->suspended = true;
        try {
            return $work();
        } finally {
            $this->suspended = false;
        }
    }

    /** A flush that failed after onFlush never reached postFlush: its changes must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->changes = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        if ($this->suspended) {
            return;
        }

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
